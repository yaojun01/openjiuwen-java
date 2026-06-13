package com.openjiuwen.runtime.alpha;

import com.openjiuwen.core.alpha.graph.*;
import com.openjiuwen.core.alpha.model.*;
import com.openjiuwen.core.kernel.model.*;
import com.openjiuwen.core.alpha.executor.SuperstepResult;
import com.openjiuwen.runtime.alpha.executor.DefaultPregelExecutor;
import com.openjiuwen.runtime.core.dispatch.TaskContext;
import com.openjiuwen.runtime.core.engine.DefaultAgentKernel;
import com.openjiuwen.runtime.core.engine.DefaultSafetyBoundary;
import com.openjiuwen.runtime.core.fixtures.MockCheckpointStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真并发证明测试——直接回应「这个并发是真的吗？还是假并发？」的质疑。
 *
 * <p>关键背景（质疑的合理性）：既有 {@code PregelExecutorTest.ConcurrencyTest.hundredNodes_noRaceCondition}
 * 用的 {@code AlphaStrategyTest.TestPregelExecutor} 对同层节点是<b>顺序 for 循环</b>（见其 execute 实现），
 * 从未真正并行——它给过虚假的安全感。本测试改用<b>生产路径的 {@link DefaultPregelExecutor}</b>
 * （虚拟线程 + Semaphore），用屏障确定性证明真并发。
 *
 * <p>两个证明维度：
 * <ol>
 *   <li><b>屏障证明</b>（{@link BarrierProofTest}）：CyclicBarrier(N) 要求 N 个节点同时在 stream() 内才放行；
 *       串行执行必死锁超时→测试失败；通过 ⟺ maxInFlight==N ⟺ 真并发。</li>
 *   <li><b>Sink 争用复现</b>（{@link SinkContentionTest}）：裸 multicast sink + N 线程密集 tryEmitNext（无锁）
 *       → 出现 FAIL_NON_SERIALIZED。该结果在单线程下物理上不可能触发，出现它 ⟺ 真并发生产者争用 CAS。</li>
 * </ol>
 */
@DisplayName("真并发证明：DefaultPregelExecutor 虚拟线程并行")
class RealConcurrencyProofTest {

    @Nested
    @DisplayName("屏障证明：同层 N 节点真并发")
    class BarrierProofTest {

        @Test
        @DisplayName("4 节点同层：maxInFlight==4 + 多线程 = 真并发，且 emit 锁保证不抛")
        void realExecutor_fourNodesSameLayer_runConcurrently() {
            int N = 4;
            // 屏障：必须 N 个节点同时在 stream() 内才放行。若执行被串行化（如 TestPregelExecutor），
            // 只有 1 个节点能到达屏障 → 永久等待 → 20s 超时 → BrokenBarrierException → 节点 FAILED → 测试失败。
            // 故本测试通过即证明：生产 DefaultPregelExecutor 确实把同层节点跑在不同虚拟线程上并发执行。
            CyclicBarrier barrier = new CyclicBarrier(N);
            // 用 threadId()（每虚拟线程唯一）而非 getName()：虚拟线程默认名是空串 ""，
            // 多个 vthread 名都="" 会让 Set 退化为 {""}（toString 恰为 "[]"，误导成空）。
            Set<Long> seenThreads = ConcurrentHashMap.newKeySet();
            AtomicInteger inFlight = new AtomicInteger(0);
            AtomicInteger maxInFlight = new AtomicInteger(0);

            // 屏障式 LLM：stream() 内记录线程 + 计入在册 + 等屏障。
            DefaultAgentKernel.LLMProvider llm = new DefaultAgentKernel.LLMProvider() {
                @Override public String call(String p) { return "ok"; }
                @Override public Flux<String> stream(String prompt) {
                    return Flux.defer(() -> {
                        seenThreads.add(Thread.currentThread().threadId());
                        int n = inFlight.incrementAndGet();
                        maxInFlight.accumulateAndGet(n, (a, b) -> a > b ? a : b);
                        try {
                            barrier.await(20, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                        return Flux.just("result-" + prompt);
                    });
                }
            };

            DefaultAgentKernel kernel = new DefaultAgentKernel(
                llm, Map.of(), new MockCheckpointStore(), new DefaultSafetyBoundary());

            // N 个 LLM_CALL 节点，无依赖 → 拓扑分层后落入同一层
            List<TaskNode> nodes = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                nodes.add(TaskNode.of("N" + i, "节点" + i, TaskNodeType.LLM_CALL));
            }
            TaskGraph graph = new TaskGraph("屏障并发证明", nodes, List.of());

            // 真实生产 executor（非 TestPregelExecutor）。executor 仅消费 context.kernel()，其余字段可空。
            TaskContext ctx = new TaskContext(
                TaskId.generate(), null, TaskInput.of("test"), null, kernel,
                Budget.Fixed.developmentDefault(), null, Map.of());
            try (DefaultPregelExecutor executor = new DefaultPregelExecutor(ctx)) {
                ExecutionPolicy policy = new ExecutionPolicy(
                    PlanningMode.AUTO, VerifyMode.NONE, 1, N, false);

                List<SuperstepResult> results = executor.execute(
                    TaskId.generate(), graph, policy,
                    BudgetLimits.start(new Budget.Fixed(N + 10, 10, 1_000_000L, 0L))
                ).collectList().block(Duration.ofSeconds(60));

                assertNotNull(results);
                assertEquals(1, results.size(), "N 个无依赖节点应在 1 个超步内完成");
                SuperstepResult step = results.get(0);
                assertTrue(step.failedNodes().isEmpty(),
                    "不应有失败节点（屏障证明并发 + emit 锁保证不抛 Spec1.3）: " + step.failedNodes());
                assertEquals(N, step.nodeResults().size());

                // —— 真并发硬断言 ——
                assertEquals(N, maxInFlight.get(),
                    "maxInFlight 必须 == N：N 个节点同时在 stream() 内（屏障放行的前提）。" +
                    "若 == 1，则同层节点被串行化了（假并发）。实测 maxInFlight=" + maxInFlight.get());
                assertTrue(seenThreads.size() >= 2,
                    "至少 2 个不同线程执行过节点（预期 " + N + " 个虚拟线程各一）: " + seenThreads);
            }
        }
    }

    @Nested
    @DisplayName("Sink 争用复现：并发 emit 真的触发 FAIL_NON_SERIALIZED")
    class SinkContentionTest {

        @Test
        @DisplayName("裸 multicast sink + N 线程密集 tryEmitNext（无锁）→ 出现 FAIL_NON_SERIALIZED")
        void bareMulticastSink_concurrentEmit_yieldsFailNonSerialized() throws Exception {
            int N = 8;
            int iterPerThread = 5000;
            // 与 DefaultAgentKernel 完全相同的 sink 类型
            Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
            // 单个无界订阅者：drain 消费，避免 FAIL_OVERFLOW 干扰，让 FAIL_NON_SERIALIZED 成为唯一争用信号
            sink.asFlux().subscribe(s -> { });

            AtomicInteger failNonSerialized = new AtomicInteger(0);
            AtomicInteger success = new AtomicInteger(0);
            CountDownLatch done = new CountDownLatch(N);

            ExecutorService pool = Executors.newFixedThreadPool(N);
            for (int t = 0; t < N; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < iterPerThread; i++) {
                            Sinks.EmitResult r = sink.tryEmitNext("x");
                            if (r == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                                failNonSerialized.incrementAndGet();
                            } else if (r.isSuccess()) {
                                success.incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdown();

            // 真并发硬断言：FAIL_NON_SERIALIZED 是 multicast safe sink 在并发生产者争用 CAS 时才返回的；
            // 单线程串行 emit 物理上不可能触发它。出现它 ⟺ 存在真实并发 emit。这就是修复前 Spec1.3 违例的机制根源。
            assertTrue(failNonSerialized.get() > 0,
                "应观察到 FAIL_NON_SERIALIZED。实测 success=" + success.get()
                    + " FAIL_NON_SERIALIZED=" + failNonSerialized.get()
                    + "。若 == 0 则要么未真正并发，要么 sink 不争用——二者都与诊断矛盾。");
            System.out.println("[SinkContention] success=" + success.get()
                + " FAIL_NON_SERIALIZED=" + failNonSerialized.get()
                + " —— 裸 multicast sink 在真并发下确实争用 CAS（这正是修复前抛 Sinks$EmissionException 的机制）");
        }
    }
}
