/*
 * Copyright 2019 IBM All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.fabric.traces.impl;

import com.google.common.io.Closer;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import org.hyperledger.fabric.contract.ChaincodeStubNaiveImpl;
import org.hyperledger.fabric.metrics.Metrics;
import org.hyperledger.fabric.protos.peer.Chaincode;
import org.hyperledger.fabric.protos.peer.ChaincodeGrpc;
import org.hyperledger.fabric.protos.peer.ChaincodeShim;
import org.hyperledger.fabric.protos.peer.ChaincodeSupportGrpc;
import org.hyperledger.fabric.shim.ChaincodeBase;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ResponseUtils;
import org.hyperledger.fabric.shim.impl.ChaincodeSupportClient;
import org.hyperledger.fabric.shim.impl.InvocationTaskManager;
import org.hyperledger.fabric.traces.Traces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public final class OpenTelemetryTracesProviderTest {

    private static final class FakeCollector extends TraceServiceGrpc.TraceServiceImplBase {
        private final List<ResourceSpans> receivedSpans = new ArrayList<ResourceSpans>();
        private Status returnedStatus = Status.OK;

        @Override
        public void export(
                final ExportTraceServiceRequest request,
                final StreamObserver<ExportTraceServiceResponse> responseObserver) {
            receivedSpans.addAll(request.getResourceSpansList());
            responseObserver.onNext(ExportTraceServiceResponse.newBuilder().build());
            if (!returnedStatus.isOk()) {
                if (returnedStatus.getCode() == Status.Code.DEADLINE_EXCEEDED) {
                    // Do not call onCompleted to simulate a deadline exceeded.
                    return;
                }
                responseObserver.onError(returnedStatus.asRuntimeException());
                return;
            }
            responseObserver.onCompleted();
        }

        List<io.opentelemetry.proto.trace.v1.ResourceSpans> getReceivedSpans() {
            return receivedSpans;
        }

        void setReturnedStatus(final Status returnedStatus) {
            this.returnedStatus = returnedStatus;
        }
    }

    private class ContextGetterChaincode extends ChaincodeBase {

        @Override
        public Response init(final ChaincodeStub stub) {
            return ResponseUtils.newSuccessResponse();
        }

        @Override
        public Response invoke(final ChaincodeStub stub) {
            return ResponseUtils.newSuccessResponse("OK response");
        }

        @Override
        public Properties getChaincodeConfig() {
            return new Properties();
        }
    }

    private final FakeCollector fakeTracesCollector = new FakeCollector();
    private final Closer closer = Closer.create();

    @BeforeEach
    public void setUp() throws Exception {
        Server server =
                NettyServerBuilder.forPort(4317)
                        .addService(fakeTracesCollector)
                        .build()
                        .start();
        closer.register(server::shutdownNow);
    }

    @AfterEach
    public void tearDown() throws Exception {
        closer.close();
    }

    @Test
    public void testProvider() {
        OpenTelemetryTracesProvider provider = new OpenTelemetryTracesProvider();
        provider.initialize(new Properties());
        ChaincodeStub stub = new ChaincodeStubNaiveImpl();
        Span span = provider.createSpan(stub);
        assertThat(span.isRecording()).isTrue();
        assertThat(provider.createInterceptor()).isNotNull();
    }

    @Test
    public void testTracing() throws Exception {
        Properties props = new Properties();
        props.put("CHAINCODE_TRACES_ENABLED", "true");
        props.put("CHAINCODE_TRACES_PROVIDER", OpenTelemetryTracesProvider.class.getName());
        props.put("OTEL_TRACES_SAMPLER", "always_on");
        props.put("OTEL_BSP_SCHEDULE_DELAY", "100");
        props.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        props.put("OTEL_EXPORTER_OTLP_INSECURE", "true");
        Traces.initialize(props);
        Metrics.initialize(props);

        // set up a grpc server in process
        ServerCallHandler<ChaincodeShim.ChaincodeMessage, ChaincodeShim.ChaincodeMessage> handler = (call, headers) -> {
            call.close(Status.OK, headers);
            return new ServerCall.Listener<ChaincodeShim.ChaincodeMessage>() {
            };
        };

        ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(ChaincodeGrpc.getServiceDescriptor()).
                addMethod(ServerMethodDefinition.create(ChaincodeGrpc.getConnectMethod(), handler));
        ServerServiceDefinition.Builder supportBuilder = ServerServiceDefinition.builder(ChaincodeSupportGrpc.getServiceDescriptor()).
                addMethod(ServerMethodDefinition.create(ChaincodeSupportGrpc.getRegisterMethod(), handler));

        String uniqueName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(uniqueName)
                .directExecutor()
                .addService(builder.build())
                .addService(supportBuilder.build())
                .build().start();

        // create our client
        ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forName(uniqueName);
        ContextGetterChaincode chaincode = new ContextGetterChaincode();
        ChaincodeSupportClient chaincodeSupportClient = new ChaincodeSupportClient(channelBuilder);

        InvocationTaskManager itm = InvocationTaskManager.getManager(chaincode, Chaincode.ChaincodeID.newBuilder().setName("foo").build());

        CompletableFuture<Void> wait = new CompletableFuture<>();
        StreamObserver<ChaincodeShim.ChaincodeMessage> requestObserver = chaincodeSupportClient.getStub().register(

                new StreamObserver<ChaincodeShim.ChaincodeMessage>() {
                    @Override
                    public void onNext(final ChaincodeShim.ChaincodeMessage chaincodeMessage) {
                        // message off to the ITM...
                        itm.onChaincodeMessage(chaincodeMessage);
                    }

                    @Override
                    public void onError(final Throwable t) {
                        chaincodeSupportClient.shutdown(itm);
                        wait.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        chaincodeSupportClient.shutdown(itm);
                        wait.complete(null);
                    }
                }

        );

        chaincodeSupportClient.start(itm, requestObserver);
        wait.get(5, TimeUnit.SECONDS);
        Thread.sleep(5000); // wait for async send of the trace
        List<io.opentelemetry.proto.trace.v1.ResourceSpans> spans = fakeTracesCollector.getReceivedSpans();
        assertThat(spans.isEmpty()).isFalse();

        chaincodeSupportClient.shutdown(itm);
        server.shutdown();
    }

}
