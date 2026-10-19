package com.example.ordersapi.pricing;

import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/** Collects a response body into a byte array, failing once it exceeds {@code maxBytes}. */
final class LimitedBodySubscriber implements BodySubscriber<byte[]> {

    private final BodySubscriber<byte[]> delegate = BodySubscribers.ofByteArray();
    private final long maxBytes;
    private Flow.@Nullable Subscription subscription;
    private long received;
    private boolean failed;

    LimitedBodySubscriber(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (failed) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            received += buffer.remaining();
        }
        if (received > maxBytes) {
            failed = true;
            if (subscription != null) {
                subscription.cancel();
            }
            delegate.onError(new IOException("pricing response larger than " + maxBytes + " bytes"));
            return;
        }
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable error) {
        if (!failed) {
            delegate.onError(error);
        }
    }

    @Override
    public void onComplete() {
        if (!failed) {
            delegate.onComplete();
        }
    }
}
