package com.github.zzt93.syncer.consumer.output.channel.jdbc;

import com.github.zzt93.syncer.common.data.SyncData;
import com.github.zzt93.syncer.common.data.SyncWrapper;
import com.github.zzt93.syncer.config.pipeline.common.MysqlConnection;
import com.github.zzt93.syncer.config.pipeline.output.PipelineBatch;
import com.github.zzt93.syncer.config.pipeline.output.RowMapping;
import com.github.zzt93.syncer.consumer.ack.Ack;
import com.github.zzt93.syncer.consumer.output.batch.BatchBuffer;
import com.github.zzt93.syncer.consumer.output.channel.BufferedChannel;
import com.mysql.jdbc.Driver;
import java.sql.BatchUpdateException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author zzt
 */
public class MysqlChannel implements BufferedChannel {

  private final Logger logger = LoggerFactory.getLogger(MysqlChannel.class);
  private final BatchBuffer<SyncWrapper> batchBuffer;
  private final PipelineBatch batch;
  private final JdbcTemplate jdbcTemplate;
  private final SQLMapper sqlMapper;
  private final Ack ack;

  public MysqlChannel(MysqlConnection connection, RowMapping rowMapping,
      PipelineBatch batch, Ack ack) {
    jdbcTemplate = new JdbcTemplate(dataSource(connection, Driver.class.getName()));
    batchBuffer = new BatchBuffer<>(batch, SyncWrapper.class);
    sqlMapper = new NestedSQLMapper(rowMapping, jdbcTemplate);
    this.batch = batch;
    this.ack = ack;
  }

  private DataSource dataSource(MysqlConnection connection, String className) {
    DataSourceProperties properties = new DataSourceProperties();
    properties.setUrl(connection.toConnectionUrl());
    properties.setUsername(connection.getUser());
    properties.setPassword(connection.getPassword());
//    properties.setDriverClassName(className);

    DataSource dataSource = (DataSource) properties.initializeDataSourceBuilder()
        .type(DataSource.class).build();
    DatabaseDriver databaseDriver = DatabaseDriver
        .fromJdbcUrl(properties.determineUrl());
    String validationQuery = databaseDriver.getValidationQuery();
    if (validationQuery != null) {
      dataSource.setTestOnBorrow(true);
      dataSource.setValidationQuery(validationQuery);
    }
    return dataSource;
  }

  @Override
  public boolean output(SyncData event) {
    String sql = sqlMapper.map(event);
    logger.debug("Convert event to sql: {}", sql);
    boolean add = batchBuffer
        .add(new SyncWrapper<>(event, sql));
    flushIfReachSizeLimit();
    return add;
  }

  @Override
  public String des() {
    return "MysqlChannel{" +
        "jdbcTemplate=" + jdbcTemplate +
        '}';
  }

  @Override
  public long getDelay() {
    return batch.getDelay();
  }

  @Override
  public TimeUnit getDelayUnit() {
    return batch.getDelayTimeUnit();
  }

  @Override
  public void flush() {
    SyncWrapper<String>[] sqls = batchBuffer.flush();
    if (sqls.length != 0) {
      logger.debug("Flush batch of {}", Arrays.toString(sqls));
      batchAndRetry(sqls);
    }
  }

  private void batchAndRetry(SyncWrapper<String>[] sqls) {
    try {
      Stream<String> stringStream = Arrays.stream(sqls).map(SyncWrapper::getData);
      jdbcTemplate.batchUpdate(stringStream.toArray(String[]::new));
    } catch (DataAccessException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BatchUpdateException) {
        int[] updateCounts = ((BatchUpdateException) cause).getUpdateCounts();
        for (int i = 0; i < updateCounts.length; i++) {
          if (updateCounts[i] == Statement.EXECUTE_FAILED) {
            if (sqls[i].retryCount() < batch.getMaxRetry()) {
              batchBuffer.addFirst(sqls[i]);
            } else {
              // TODO 18/1/18 fail log
              logger.error("Max retry exceed, write to fail.log");
            }
          } else {
            ack.remove(sqls[i].getSourceId(), sqls[i].getSyncDataId());
          }
        }
      }
      logger.error("{}", Arrays.toString(sqls), e);
    }
  }

  @Override
  public void flushIfReachSizeLimit() {
    SyncWrapper<String>[] sqls = batchBuffer.flushIfReachSizeLimit();
    if (sqls != null) {
      logger.debug("Flush when reach size limit, send batch of {}", Arrays.toString(sqls));
      batchAndRetry(sqls);
    }
  }
}
