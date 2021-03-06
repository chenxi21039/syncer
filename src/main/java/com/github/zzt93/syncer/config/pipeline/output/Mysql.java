package com.github.zzt93.syncer.config.pipeline.output;

import com.github.zzt93.syncer.config.pipeline.common.InvalidConfigException;
import com.github.zzt93.syncer.config.pipeline.common.MysqlConnection;
import com.github.zzt93.syncer.consumer.ack.Ack;
import com.github.zzt93.syncer.consumer.output.channel.OutputChannel;
import com.github.zzt93.syncer.consumer.output.channel.jdbc.MysqlChannel;

/**
 * @author zzt
 */
public class Mysql implements OutputChannelConfig {

  private MysqlConnection connection;
  private RowMapping rowMapping;
  private PipelineBatch batch = new PipelineBatch();

  public MysqlConnection getConnection() {
    return connection;
  }

  public void setConnection(
      MysqlConnection connection) {
    this.connection = connection;
  }

  public PipelineBatch getBatch() {
    return batch;
  }

  public void setBatch(PipelineBatch batch) {
    this.batch = batch;
  }

  public RowMapping getRowMapping() {
    return rowMapping;
  }

  public void setRowMapping(RowMapping rowMapping) {
    this.rowMapping = rowMapping;
  }

  @Override
  public OutputChannel toChannel(Ack ack) throws Exception {
    if (connection.valid()) {
      return new MysqlChannel(connection, rowMapping, batch, ack);
    }
    throw new InvalidConfigException("Invalid connection configuration: " + connection);
  }

}
