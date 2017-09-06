package com.github.zzt93.syncer.input.connect;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.zzt93.syncer.config.InvalidPasswordException;
import com.github.zzt93.syncer.config.input.Master;
import com.github.zzt93.syncer.input.listener.LogLifecycleListener;
import com.github.zzt93.syncer.input.listener.SyncListener;
import com.github.zzt93.syncer.util.FileUtil;
import com.github.zzt93.syncer.util.NetworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author zzt
 */
public class MasterConnector {

    private static final ExecutorService service = Executors.newFixedThreadPool(4, new NamedThreadFactory());
    private final String remote;
    private Logger logger = LoggerFactory.getLogger(MasterConnector.class);
    private BinaryLogClient client;

    public MasterConnector(Master master) throws IOException {
        String password = FileUtil.readAll(master.getPasswordFile());
        if (StringUtils.isEmpty(password)) {
            throw new InvalidPasswordException(password);
        }
        client = new BinaryLogClient(master.getAddress(), master.getPort(), master.getUser(), password);
        client.registerEventListener(new SyncListener());
        client.registerLifecycleListener(new LogLifecycleListener());

        this.remote = NetworkUtil.toIp(master.getAddress()) + ":" + master.getPort();
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
