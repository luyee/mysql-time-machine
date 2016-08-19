#!/usr/bin/python

import click
from datetime import datetime
import logging
import MySQLdb
import MySQLdb.cursors
import sys
import Queue
import threading
from threading import Thread
import signal
from time import sleep
from operator import itemgetter


class Cursor(MySQLdb.cursors.CursorStoreResultMixIn, MySQLdb.cursors.CursorTupleRowsMixIn, MySQLdb.cursors.BaseCursor):
    pass


class CopyProcess(Thread):
    def __init__(self, queue, config, main):
        super(CopyProcess, self).__init__()
        self.queue = queue
        self.config = config
        self.main = main

    def run(self):
        while not self.queue.empty():
            table = self.queue.get()
            if 'table' in self.config and table[1] not in self.config['table']:
                continue
            self.main.do_copy(self.config, table)
            self.queue.task_done()


class ConnectionDispatch(Thread):
    def __init__(self, config):
        super(ConnectionDispatch, self).__init__()
        self.connection_ids = []
        self.die = False
        self.daemon = True
        self.config = config


    def get_source(self):
        src = MySQLdb.connect(read_default_file=self.config['source'], cursorclass=Cursor, host=self.config['host'])
        self._add_id(src.thread_id())
        return src

    def _add_id(self, tid):
        logger.info("thread id {} added..".format(tid))
        self.connection_ids += [tid]

    def _reset_connection(self):
        self.connection_ids = []

    def terminate(self):
        self.die = True

    def run(self):
        while not self.die:
            pass
        # We create another MySQL connection to kill all the process
        logger.info("Terminating all connections")
        hc_killer = MySQLdb.connect(read_default_file=self.config['source'], cursorclass=Cursor, host=self.config['host'])
        hc_cursor = hc_killer.cursor()
        for tid in self.connection_ids:
            logger.info("Killing thread id: {}".format(tid))
            try:
                hc_cursor.execute('kill {}'.format(tid))
            except MySQLdb.OperationalError:
                logger.warn("Thread {} doesn't exist".format(tid))
        self._reset_connection()
        hc_killer.close()

class BlackholeCopyMethod(object):
    def __init__(self, config, tables, condis):
        self.hashCount = 0
        self.hashRep = {}
        self.MAX_RETRIES = 3
        self.config = config
        self.tables = tables
        self.threads = []
        self.queue = Queue.Queue()
        self.conDis = condis

    def queue_tables(self, config, tables):
        for table in tables:
            self.queue.put(table)

    def consumer_queue(self):
        for i in range(min(self.queue.qsize(), 16)):
            p = CopyProcess(self.queue, self.config, self)
            p.daemon = True
            p.start()
            self.threads.append(p)

    def join(self):
        try:
            while threading.active_count() > 2:
                pass
                # Fake join
        except:
            logger.warn("There is an exception, probably you stop the process!")
            self.conDis.terminate()

    def has_key(self, table_name):
        return table_name in self.hashRep

    def remove_key(self, table_name):
        self.hashRep.pop(table_name)

    def get_hash(self, table_name):
        if not self.has_key(table_name):
            if table_name[0:6] == '_BKTB_':
                self.post()
                logger.error("Broken database")
                raise Exception("Did you forget to recover the database? Try to run db-recovery.py first!")
            self.hashCount += 1
            with open (hashmapFileName, 'a') as f:
                f.write("_BKTB_%d,%s\n" % (self.hashCount, table_name))
            logger.info("Mapper %s to _BKTB_%d" % (table_name, self.hashCount))
            return '_BKTB_%d' % self.hashCount
        return self.hashRep[table_name]

    def set_hash(self, table_name, hash_table_name):
        self.hashRep[table_name] = hash_table_name

    def pre(self, config, tables):
        source = self.conDis.get_source()
        cursor = source.cursor()
        done = []
        sql = 'reset master'
        logger.info(sql)
        cursor.execute(sql)
        for table in tables:
            if (table[0], table[1]) in done:
                continue
            sql = 'show create table `{}`.`{}`;'.format(table[0], table[1])
            logger.info(sql)
            cursor.execute(sql)
            create_table_sql =  cursor.fetchall()[0][1]
            sql = 'use {}'.format(table[0])
            logger.info(sql)
            cursor.execute(sql)
            sql = 'set sql_log_bin=0'
            logger.info(sql)
            cursor.execute(sql)
            hash_table_name = self.get_hash(table[1])
            sql = 'rename table `{}`.`{}` to `{}`.`{}`;'.format(table[0], table[1], table[0], hash_table_name)
            logger.info(sql)
            cursor.execute(sql)
            self.set_hash(table[1], hash_table_name)
            cursor = source.cursor()
            sql = 'set sql_log_bin=1'
            logger.info(sql)
            cursor.execute(sql)
            sql = 'create table `{}`.`{}` like `{}`.`{}`;'.format(table[0], table[1], table[0], self.get_hash(table[1]))
            sql = create_table_sql
            logger.info(sql)
            cursor.execute(sql)
            cursor = source.cursor()
            sql = 'set sql_log_bin=0'
            logger.info(sql)
            cursor.execute(sql)
            sql = 'alter table `{}`.`{}` engine=blackhole;'.format(table[0], table[1])
            logger.info(sql)
            cursor.execute(sql)
            sql = 'set sql_log_bin=1'
            logger.info(sql)
            cursor.execute(sql)
            done.append((table[0], table[1]))

    def do_copy(self, config, table):
        source = self.conDis.get_source()
        cursor = source.cursor()
        if len(table) == 3:
            sql = """SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = %s and table_name = %s and column_key='PRI'"""
            logger.info(sql) % (table[0], table[1])
            cursor.execute(sql, (table[0], table[1]))
            primary_key = cursor.fetchall()
            if len(primary_key) == 1 and primary_key[0][1] in ['tinyint', 'smallint', 'int', 'bigint']:
                self.chunked_copy(config, table, primary_key[0][0])
                return
            sql = 'INSERT INTO `{}`.`{}` SELECT * FROM `{}`.`{}` PARTITION ({})'.format(table[0], table[1], table[0], self.get_hash(table[1]), table[2])
        else:
            sql = 'INSERT INTO `{}`.`{}` SELECT * FROM `{}`.`{}`'.format(table[0], table[1], table[0], self.get_hash(table[1]))
        logger.info(sql)
        executed = False
        retryTimes = 0
        while not executed:
            try:
                cursor.execute(sql)
                source.commit()
                executed = True
            except Exception, MySQLdb.OperationalError:
                retryTimes += 1
                if retryTimes == self.MAX_RETRIES:
                    break
                source = self.conDis.get_source()
                cursor = source.cursor()
        if not executed:
            self.post()
            logger.error("We are unable to insert into Table: %s after %d retries" % (table, self.MAX_RETRIES))
        cursor.close()
        logger.info("copy finished")

    def chunked_copy(self, config, table, primary_key):
        source = self.conDis.get_source()
        cursor = source.cursor()
        sql = 'SELECT `{}` FROM `{}`.`{}` PARTITION ({})'.format(primary_key, table[0], self.get_hash(table[1]), table[2])
        logger.info(sql)
        cursor.execute(sql)
        ids = cursor.fetchall()
        cursor.close()
        ids = map(itemgetter(0), ids)
        cursor = source.cursor()
        offset = 0
        limit = 999
        while offset < len(ids):
            chunk = ids[offset:limit]
            sql = 'INSERT INTO `{}`.`{}` SELECT * FROM `{}`.`{}` PARTITION ({}) WHERE id IN ({})'.format(table[0], table[1], table[0], self.get_hash(table[1]), table[2], ','.join([str(i) for i in chunk]))
            logger.info('Inserting chunk {} to {}'.format(min(chunk), max(chunk)))
            executed = False
            retryTimes = 0
            while not executed:
                try:
                    cursor.execute(sql)
                    source.commit()
                    offset = limit
                    limit += 1000
                    executed = True
                except Exception, MySQLdb.OperationalError:
                    retryTimes += 1
                    if retryTimes == self.MAX_RETRIES:
                        break
                    source = self.conDis.get_source()
                    cursor = source.cursor()
            if not executed:
                self.post()
                logger.error("We are unable to insert into Table: %s after %d retries" % (table, self.MAX_RETRIES))
        cursor.close()

    def post(self):
        print "Please wait while the DB is cleaning up..."
        source = self.conDis.get_source()
        cursor = source.cursor()
        sql = 'set sql_log_bin=0'
        logger.info(sql)
        cursor.execute(sql)
        for table in self.tables:
            if self.has_key(table[1]):
                sql = 'drop table if exists `{}`.`{}`;'.format(table[0], table[1])
                logger.info(sql)
                cursor.execute(sql)
                sql = 'rename table `{}`.`{}` to `{}`.`{}`;'.format(table[0], self.get_hash(table[1]), table[0], table[1])
                logger.info(sql)
                cursor.execute(sql)
                self.remove_key(table[1])
        sql = 'set sql_log_bin=1'
        logger.info(sql)
        cursor.execute(sql)
        self.conDis.terminate()


class DataFlusher(object):
    def get_tables(self, conDis, config):
        source = conDis.get_source()
        cursor = source.cursor()
        cursor.execute('SELECT table_schema, table_name from information_schema.tables')
        tables = cursor.fetchall()
        cursor.close()
        cursor = source.cursor()
        cursor.execute('SELECT table_schema, table_name, partition_name from information_schema.partitions where partition_name is not null')
        partitions = cursor.fetchall()
        cursor.close()
        result = []
        for table in tables:
            if 'db' in config and table[0] not in config['db']:
                continue
            if table[0] in config['skip']:
                continue
            partitioned = False
            for partition in partitions:
                if partition[1] == table[1]:
                    result.append(partition)
                    partitioned = True
            if not partitioned:
                result.append(table)
        return result


@click.command()
@click.option('--mycnf', default='~/my.cnf', help='my.cnf with connection settings')
@click.option('--db', required=False, help='comma separated list of databases to copy. Leave blank for all databases')
@click.option('--table', required=False, help='comma separated list of tables to copy. Leave blank for all tables')
@click.option('--stop-slave/--no-stop-slave', default=True, help='stop the replication thread whilst running the copy')
@click.option('--start-slave/--no-start-slave', default=False, help='restart the replication thread after running the copy')
@click.option('--method', default='BlackholeCopy', help='Copy method class')
@click.option('--host', help='Host name')
@click.option('--skip', required=False, help='comma separated list of skip schemas')
def run(mycnf, db, table, stop_slave, start_slave, method, host, skip):
    flusher = DataFlusher()
    config = {
        'source' : mycnf,
        'skip': ['sys', 'mysql', 'information_schema', 'performance_schema']
    }
    if host:
        config['host'] = host
    if db:
        config['db'] = db.split(',')
    if table:
        config['table'] = table.split(',')
    if skip:
        config['skip'].append(skip.split(','))
    conDis = ConnectionDispatch(config)
    conDis.start()
    tables = flusher.get_tables(conDis, config)
    source = conDis.get_source()
    cursor = source.cursor()
    if stop_slave:
        sql = 'stop slave'
        logger.info(sql)
        cursor.execute(sql)
    if method == 'BlackholeCopy':
        # Isn't it the only method?
        main = BlackholeCopyMethod(config, tables, conDis)
    main.pre(config, tables)
    main.queue_tables(config, tables)
    main.consumer_queue()
    main.join()
    main.post()
    if start_slave:
        sql = 'start slave'
        logger.info(sql)
        cursor.execute(sql)
    sys.exit(0)

if __name__ == '__main__':
    logger = logging.getLogger('dataFlusher')
    curTime = datetime.now()
    hdlr = logging.FileHandler('dataFlusher-%s.log' % curTime)
    hashmapFileName = 'flusherHash-%s.txt' % curTime
    formatter = logging.Formatter('%(asctime)s %(levelname)s %(message)s')
    hdlr.setFormatter(formatter)
    logger.addHandler(hdlr)
    logger.setLevel(logging.INFO)
    run()
