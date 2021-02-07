package org.opendaylight.infrautils.jobcoordinator.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.StreamSupport;

public final class MoreGuavaFutures {


    private MoreGuavaFutures() {
        throw new RuntimeException("Don't initiated this utility class.");
    }

    /**
     * 与 {@code guava} 库的 {@code Futures#allAsList} 及 {@code Futures#successFulAsList} 类似，
     * 将多个 {@code future} 转为单个 {@code future}；不同的是返回的 {@code future} 会在所有传入的
     * {@code future} 全部成功或全部失败后才会结束；如果返回的 {@code future} 被 {@code cancel}，
     * 会尝试调用所有传入 {@code futures} 的 {@code cancel}。如果 {@code futures} 中有失败的，最终返回的
     * {@code future} 会获得失败信息，异常会是 {@linkplain java.util.concurrent.ExecutionException}，内部的
     * {@code cause} 会是 {@linkplain Exception}；如果输入的 {@code futures} 被取消，
     * 返回的 {@code future} 的 {@linkplain ListenableFuture#isCancelled()} 不是 {@code true}；只有调用者
     * 主动取消才会是 {@code true}.
     *
     * @param futures 输入的多个 future
     * @param <V>     返回值类型
     * @return 返回一个用于反映所有输入 futures 结果的 future
     */
    public static <V> ListenableFuture<List<V>> allAsList(ListenableFuture<? extends V>... futures) {
        return allAsList(ImmutableList.copyOf(futures));
    }

    /**
     * 与 {@code guava} 库的 {@code Futures#allAsList} 及 {@code Futures#successFulAsList} 类似，
     * 将多个 {@code future} 转为单个 {@code future}；不同的是返回的 {@code future} 会在所有传入的
     * {@code future} 全部成功或全部失败后才会结束；如果返回的 {@code future} 被 {@code cancel}，
     * 会尝试调用所有传入 {@code futures} 的 {@code cancel}。如果 {@code futures} 中有失败的，最终返回的
     * {@code future} 会获得失败信息，异常会是 {@linkplain java.util.concurrent.ExecutionException}，内部的
     * {@code cause} 会是 {@linkplain Exception}；如果输入的 {@code futures} 被取消，
     * 返回的 {@code future} 的 {@linkplain ListenableFuture#isCancelled()} 不是 {@code true}；只有调用者
     * 主动取消才会是 {@code true}.
     *
     * @param futures 输入的多个 future
     * @param <V>     返回值类型
     * @return 返回一个用于反映所有输入 futures 结果的 future
     */
    @SuppressWarnings("IllegalCatch")
    public static <V> ListenableFuture<List<V>> allAsList(Iterable<? extends ListenableFuture<? extends V>> futures) {
        if (null == futures || Iterables.isEmpty(futures)) {
            return Futures.immediateFuture(Collections.emptyList());
        }
        SettableFuture<List<V>> futureListResult = SettableFuture.create();
        ListenableFuture<List<V>> futureList = Futures.successfulAsList(futures);
        Futures.addCallback(futureList, new FutureCallback<List<V>>() {
            @Override
            public void onSuccess(List<V> result) {
                Map<ListenableFuture<?>, Throwable> causes = new HashMap<>();
                StreamSupport.stream(futures.spliterator(), false).forEach(future -> {
                    try {
                        // 这里由于 guava 库的原因，future 应该已经结束；一般不会是上面这个异常
                        if (!future.isDone()) {
                            causes.put(future,
                                new CancellationException("future " + future + "may be canceled or timeouted."));
                        } else {
                            future.get();
                        }
                    } catch (Throwable ex) {
                        causes.put(future, ex);
                    }
                });
                if (causes.isEmpty()) {
                    futureListResult.set(result);
                } else {
                    futureListResult.setException(new Exception());
                }
            }

            @Override
            public void onFailure(Throwable ex) {
                futureListResult.setException(ex);
            }
        }, MoreExecutors.directExecutor());
        Futures.addCallback(futureListResult, new FutureCallback<List<V>>() {
            @Override
            public void onSuccess(List<V> result) {
                // pass
            }

            @Override
            public void onFailure(Throwable ex) {
                if (null != ex
                    && (ex instanceof CancellationException || ex.getCause() instanceof CancellationException)) {
                    futures.forEach(future -> {
                        if (!future.isCancelled()) {
                            future.cancel(false);
                        }
                    });
                }
            }
        }, MoreExecutors.directExecutor());
        return futureListResult;
    }

}
