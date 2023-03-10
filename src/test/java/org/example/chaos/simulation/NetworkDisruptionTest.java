package org.example.chaos.simulation;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.NetworkSettings;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import org.jetbrains.annotations.NotNull;
import org.junit.ClassRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.test.StepVerifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
public class NetworkDisruptionTest
{

    private final static String EMITTER_SERVICE = "emitter";
    private final static String TOXI_PROXY_SERVICE = "toxyproxy";

    static {
        Hooks.onOperatorDebug();
    }

    private static final Logger logger = LoggerFactory.getLogger(NetworkDisruptionTest.class);

    @ClassRule
    private DockerComposeContainer<?> environment = new DockerComposeContainer<>(new File("src/test/docker/docker-compose.yaml"))
            .withExposedService(EMITTER_SERVICE, 8090)
            .withExposedService(TOXI_PROXY_SERVICE, 8474)
            .withExposedService(TOXI_PROXY_SERVICE, 8091) // proxy to 8090
            .withLogConsumer(EMITTER_SERVICE, new Slf4jLogConsumer(logger))
            .withLogConsumer(TOXI_PROXY_SERVICE, new Slf4jLogConsumer(logger))
            .waitingFor(EMITTER_SERVICE, Wait.forListeningPort())
            .waitingFor(TOXI_PROXY_SERVICE, Wait.forListeningPort());


    private ContainerState emitterContainer;
    private ContainerState toxiProxyContainer;

    @BeforeEach
    public void beforeEach()
    {
        environment.start();
        emitterContainer = environment.getContainerByServiceName(EMITTER_SERVICE).get();
        toxiProxyContainer = environment.getContainerByServiceName(TOXI_PROXY_SERVICE).get();
    }

    @AfterEach
    public void afterEach()
    {
        environment.stop();
        emitterContainer = null;
        toxiProxyContainer = null;
    }

    private String getContainerId(ContainerState container)
    {
        return container.getContainerInfo().getId();
    }

    private NetworkSettings getContainerNetworkSettings(ContainerState containerState)
    {
        return containerState.getContainerInfo().getNetworkSettings();
    }

    private Map.Entry<String, ContainerNetwork> getContainerFirstNetwork(ContainerState container)
    {
        return getContainerNetworkSettings(container).getNetworks().entrySet().stream().findFirst().get();
    }

    private String getContainerIp(ContainerState container)
    {
        return getContainerFirstNetwork(container).getValue().getIpAddress();
    }

    private String getContainerNetworkName(ContainerState container)
    {
        return getContainerFirstNetwork(container).getKey();
    }

    /**
     * <p>Scenario:</p>
     * <ol>
     *     <li>The connection to the websocket server is established.</li>
     *     <li>Network partitioning appears -- maybe a switch died, container was killed, IPs have rotated and old ones are no longer responding</li>
     * </ol>
     *
     * <p>Expected result:</p>
     * <p>"connection interrupted" or maybe a "connection reset" exception</p>
     *
     * <p>Actual result:</p>
     * <p>Connection times out.</p>
     */
    @Test
    public void partitionSimulationFix()
    {
        final String emitterIP = getContainerIp(emitterContainer);
        final String emitterContainerId = getContainerId(emitterContainer);
        final String emitterContainerNetworkId = getContainerNetworkName(emitterContainer);
        final int emitterPort = 8090;

        // 2. Container is stopped so nothing is emitting.
        Mono.create(disconnectContainerNetwork(emitterContainerId, emitterContainerNetworkId))
            .delaySubscription(Duration.ofMillis(2345)) // deliberately odd millis
            .subscribeOn(Schedulers.boundedElastic())
            .publishOn(Schedulers.boundedElastic())
            .subscribe();

        // 1. Establish a connection
        HttpClient client = HttpClient.create()
                                      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                                      .option(ChannelOption.SO_KEEPALIVE, true)
                                      // TCP_KEEPIDLE - The maximum time (resolution: seconds) that this connection stays idle before TCP starts
                                      // sending keepalive probes, if SO_KEEPALIVE has been set. The maximum time is implementation dependent,
                                      // but the default is typically two hours.
                                      .option(EpollChannelOption.TCP_KEEPIDLE, 5)
                                      // TCP_KEEPINTVL (Epoll)/TCP_KEEPINTERVAL (NIO) - The time (resolution: seconds) between individual keepalive probes.
                                      .option(EpollChannelOption.TCP_KEEPINTVL, 3) // this should probably be something higher
                                      // TCP_KEEPCNT (Epoll)/TCP_KEEPCOUNT (NIO) - The maximum number of keepalive probes TCP should send before
                                      // dropping the connection.
                                      .option(EpollChannelOption.TCP_KEEPCNT, 2); // lower value just for this test.

        StepVerifier.create(client.websocket(WebsocketClientSpec.builder().handlePing(true).build())
                                  .uri("ws://" + emitterIP + ":" + emitterPort)
                                  .handle((inbound, outbound) -> inbound.receive().asString().log())
                                  .publishOn(Schedulers.boundedElastic())
                                  .subscribeOn(Schedulers.boundedElastic(), false)
                           )
                    .recordWith(ArrayList::new)
                    .thenConsumeWhile(o -> true)
                    .expectError()
                    .verify(Duration.ofSeconds(20));

    }

    /**
     * <p>Scenario:</p>
     * <ol>
     *     <li>The connection to the websocket server is established.</li>
     *     <li>Network partitioning appears -- maybe a switch died, container was killed, IPs have rotated and old ones are no longer responding</li>
     * </ol>
     *
     * <p>Expected result:</p>
     * <p>"connection interrupted" or maybe a "connection reset" exception</p>
     *
     * <p>Actual result:</p>
     * <p>Indefinite hanging -- threads are not blocked per say, but in a "WAITING" state for a response that will never come. The
     * {@link StepVerifier} kicks in and times out the test case, but the client should have timed out on it's own.</p>
     */
    @Test
    public void partitionSimulationFailure()
    {
        final String emitterIP = getContainerIp(emitterContainer);
        final String emitterContainerId = getContainerId(emitterContainer);
        final String emitterContainerNetworkId = getContainerNetworkName(emitterContainer);
        final int emitterPort = 8090;

        // 2. Container is stopped so nothing is emitting.
        Mono.create(disconnectContainerNetwork(emitterContainerId, emitterContainerNetworkId))
            .delaySubscription(Duration.ofMillis(2345)) // deliberately odd millis
            .subscribeOn(Schedulers.boundedElastic())
            .publishOn(Schedulers.boundedElastic())
            .subscribe();

        // 1. Establish a connection
        HttpClient client = HttpClient.create();

        assertThrows(java.lang.AssertionError.class, () -> {
            StepVerifier.create(client.websocket(WebsocketClientSpec.builder().handlePing(true).build())
                                      .uri("ws://" + emitterIP + ":" + emitterPort)
                                      .handle((inbound, outbound) -> inbound.receive().asString().log())
                                      .publishOn(Schedulers.boundedElastic())
                                      .subscribeOn(Schedulers.boundedElastic(), false)
                               )
                        .recordWith(ArrayList::new)
                        .thenConsumeWhile(o -> true)
                        .expectError()
                        .verify(Duration.ofSeconds(20));
        });

    }

    /**
     * This method will remove the container from the network causing it to be immediately inaccessible. All active connections are no longer
     * available and no "CLOSE" tcp packet is sent. It perfectly simulates network partitioning.
     *
     * @param emitterContainerId
     * @param emitterContainerNetworkId
     *
     * @return
     */
    @NotNull
    private static Consumer<MonoSink<Object>> disconnectContainerNetwork(String emitterContainerId, String emitterContainerNetworkId)
    {
        return sink -> {
            String cmd = String.format("docker network disconnect %s %s", emitterContainerNetworkId, emitterContainerId);
            logger.info("[Kill container] Attempting to disconnect container network to simulate network partitioning.");
            logger.info("[Kill container] /bin/sh -c {}", cmd);
            ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", cmd);
            try {
                Process process = processBuilder.start();
                StringBuilder output = new StringBuilder();
                StringJoiner strings = new StringJoiner("\n");
                new BufferedReader(new InputStreamReader(process.getInputStream())).lines().forEachOrdered(strings::add);
                int exitVal = process.waitFor();
                if (exitVal == 0) {
                    logger.info("[Kill container] Container is no longer in the docker-compose network!");
                    logger.error("[Kill container] `VerifySubscriber timed out` error means the test timed out, but the client did not.");
                    logger.error("[Kill container] `Errors$NativeIoException: recvAddress(..) failed: Connection timed out` means the client timed " +
                                 "out (as expected)");
                }
                else {
                    logger.error("[Kill container] Could not disconnect container from network!\n {}", output);
                    sink.error(new RuntimeException("Could not disconnect container from network"));
                }
            }
            catch (IOException | InterruptedException e) {
                logger.error("[Kill container] Error!", e);
                sink.error(e);
            }
        };
    }

}
