package com.example.ratelimiter.service.grpc;

import com.example.ratelimiter.core.Decision;
import com.example.ratelimiter.core.algorithm.LimitAlgorithm;
import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitRequest;
import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitResponse;
import io.envoyproxy.envoy.service.ratelimit.v3.RateLimitServiceGrpc;
import io.envoyproxy.envoy.config.ratelimit.v3.RateLimitDescriptor;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

@GrpcService
public class EnvoyRateLimitGrpcService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final LimitAlgorithm limitAlgorithm;

    @Autowired
    public EnvoyRateLimitGrpcService(LimitAlgorithm limitAlgorithm) {
        this.limitAlgorithm = limitAlgorithm;
    }

    @Override
    public void shouldRateLimit(RateLimitRequest request, StreamObserver<RateLimitResponse> responseObserver) {
        // We will match the first valid descriptor as the "key" for simplicity
        // e.g. "api_key" from envoy.yaml
        String limitKey = "default";
        outer:
        for (RateLimitDescriptor descriptor : request.getDescriptorsList()) {
            for (RateLimitDescriptor.Entry entry : descriptor.getEntriesList()) {
                if ("api_key".equals(entry.getKey())) {
                    limitKey = "apikey:" + entry.getValue();
                    break outer;
                }
            }
        }

        // Standard gRPC Ratelimit payload: Check exactly 1 cost against the limit
        Decision decision = limitAlgorithm.checkAndConsume(limitKey, request.getHitsAddend() > 0 ? request.getHitsAddend() : 1);

        RateLimitResponse.Builder responseBuilder = RateLimitResponse.newBuilder()
                .setOverallCode(decision.allowed() ? RateLimitResponse.Code.OK : RateLimitResponse.Code.OVER_LIMIT);

        // Inject headers into the response based on the decision
        for (var headerEntry : decision.headers().entrySet()) {
            responseBuilder.addResponseHeadersToAdd(
                    io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                            .setHeader(
                                    io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                                            .setKey(headerEntry.getKey())
                                            .setValue(headerEntry.getValue())
                                            .build()
                            )
                            .build()
            );
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
