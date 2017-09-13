package com.github.zzt93.syncer.input.connect;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.zzt93.syncer.common.SchemaMeta;
import com.github.zzt93.syncer.common.util.FileUtil;
import com.github.zzt93.syncer.common.util.NetworkUtil;
import com.github.zzt93.syncer.config.InvalidPasswordException;
import com.github.zzt93.syncer.config.common.MysqlConnection;
import com.github.zzt93.syncer.config.common.SchemaUnavailableException;
import com.github.zzt93.syncer.config.input.Schema;
import com.github.zzt93.syncer.input.filter.InputEnd;
import com.github.zzt93.syncer.input.filter.InputFilter;
import com.github.zzt93.syncer.input.filter.InputStart;
import com.github.zzt93.syncer.input.filter.SchemaFilter;
import com.github.zzt93.syncer.input.listener.LogLifecycleListener;
import com.github.zzt93.syncer.input.listener.SyncListener;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * @author zzt
 */
public class MasterConnector {

  private static final ExecutorService service = Executors
      .newFixedThreadPool(4, new NamedThreadFactory());
  private final String remote;
  private Logger logger = LoggerFactory.getLogger(MasterConnector.class);
  private BinaryLogClient client;

  public MasterConnector(MysqlConnection connection, Schema schema)
      throws IOException, SchemaUnavailableException {
    String password = FileUtil.readAll(connection.getPasswordFile());
    if (StringUtils.isEmpty(password)) {
      throw new InvalidPasswordException(password);
    }
    client = new BinaryLogClient(connection.getAddress(), connection.getPort(),
        connection.getUser(), password);
    client.registerLifecycleListener(new LogLifecycleListener());

    List<InputFilter> filters = new ArrayList<>();
    if (schema != null) {
      try {
        SchemaMeta schemaMeta = new SchemaMeta.MetaDataBuilder(connection, schema).build();
        filters.add(new SchemaFilter(schemaMeta));
      } catch (SQLException e) {
        logger.error("Fail to connect to master to retrieve schema metadata", e);
        throw new SchemaUnavailableException(e);
      }
    }
    client.registerEventListener(new SyncListener(new InputStart(), filters, new InputEnd()));

    remote = NetworkUtil.toIp(connection.getAddress()) + ":" + connection.getPort();

  }

  public void connect() throws IOException {
    service.submit(() -> {
      Thread.currentThread().setName(remote);
      try {
        client.connect();
      } catch (IOException e) {
        logger.error("Fail to connect to master", e);
      }
    });
  }
}
