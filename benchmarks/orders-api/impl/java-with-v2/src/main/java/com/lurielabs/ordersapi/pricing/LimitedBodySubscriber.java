package com.lurielabs.ordersapi.pricing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects a response body into memory, failing (and cancelling the stream) above a size limit. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maxBytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) {
            return;
        }
        for (ByteBuffer item : items) {
            if (buffer.size() + item.remaining() > maxBytes) {
                subscription.cancel();
                body.completeExceptionally(new IOException("response body larger than " + maxBytes + " bytes"));
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            buffer.write(chunk, 0, chunk.length);
        }
    }

    @Override
    public void onError(Throwable error) {
        body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        body.complete(buffer.toByteArray());
    }
}
