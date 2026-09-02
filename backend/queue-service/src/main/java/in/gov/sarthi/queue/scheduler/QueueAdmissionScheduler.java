package in.gov.sarthi.queue.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.gov.sarthi.queue.config.RabbitMQConfig;
import in.gov.sarthi.queue.dto.QueueSnapshot;
import in.gov.sarthi.queue.service.QueueManagerService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This is the component that actually protects downstream services.
 * Every {@code tick-interval-seconds}, for every active exam queue, it:
 *
 *   1. Admits a fixed number of people (ZPOPMIN) — this number is
 *      chosen to match what result-service + its Postgres instance can
 *      comfortably sustain, so admission rate becomes the throttle valve
 *      for the entire system.
 *   2. Publishes an "admitted" event to RabbitMQ per admitted user, so
 *      notification-service can push an SMS/WhatsApp alert and
 *      result-service can pre-warm its cache for that user.
 *   3. Recomputes and pushes a live position update, over WebSocket, to
 *      everyone still waiting — this is what makes the token board on
 *      the frontend feel alive instead of requiring a manual refresh.
 */
@Component
public class QueueAdmissionScheduler {

    private static final List<String> ACTIVE_EXAMS = List.of("NEET-UG-2026", "JEE-MAIN-2026", "SSC-CGL-2026");

    private final QueueManagerService queueManagerService;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueueAdmissionScheduler(QueueManagerService queueManagerService,
                                    StringRedisTemplate redis,
                                    SimpMessagingTemplate messagingTemplate,
                                    RabbitTemplate rabbitTemplate) {
        this.queueManagerService = queueManagerService;
        this.redis = redis;
        this.messagingTemplate = messagingTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedRateString = "${queue.tick-interval-seconds:2}000")
    public void tick() {
        for (String examId : ACTIVE_EXAMS) {
            String queueKey = queueManagerService.getQueueKeyForExam(examId);
            admitNext(examId, queueKey);
            broadcastRemainingPositions(examId, queueKey);
        }
    }

    private void admitNext(String examId, String queueKey) {
        Set<ZSetOperations.TypedTuple<String>> popped =
                redis.opsForZSet().popMin(queueKey, queueManagerService.getAdmitPerTick());
        if (popped == null) return;

        // Feeds adaptive wait-time estimation — see
        // QueueManagerService.getEffectiveAdmitRate(). Recorded even when
        // zero were admitted (an empty/short queue), since that's real
        // signal too, not noise to discard.
        queueManagerService.recordAdmissionSample(popped.size());

        for (ZSetOperations.TypedTuple<String> tuple : popped) {
            String queueId = tuple.getValue();
            if (queueId == null) continue;

            Map<Object, Object> meta = redis.opsForHash().entries("queue:meta:" + queueId);
            String rollNumber = (String) meta.getOrDefault("rollNumber", "unknown");
            String joiningIp = (String) meta.get("joiningIp");

            // Mint the signed admission ticket and record the hash-chained
            // audit event (QueueManagerService.admitNow), then push it to
            // the browser directly and immediately. Previously this sent
            // an un-ticketed admittedNow() snapshot, which is what let the
            // frontend fall back to forwarding the bare queueId as a
            // "ticket" that result-service never actually checked.
            QueueSnapshot snapshot = queueManagerService.admitNow(examId, queueId, rollNumber, joiningIp);
            messagingTemplate.convertAndSend("/topic/queue/" + queueId, snapshot);

            // ...and publish for other services (notification-service
            // sends the SMS/WhatsApp "your result is ready" ping) without
            // queue-service needing to know or care how that's done.
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.ADMITTED_ROUTING_KEY,
                    Map.of("queueId", queueId, "examId", examId, "rollNumber", rollNumber)
            );
        }
    }

    private void broadcastRemainingPositions(String examId, String queueKey) {
        Set<ZSetOperations.TypedTuple<String>> remaining =
                redis.opsForZSet().rangeWithScores(queueKey, 0, -1);
        if (remaining == null || remaining.isEmpty()) return;

        String status = queueManagerService.loadStatus();
        int rank = 0;
        for (ZSetOperations.TypedTuple<String> tuple : remaining) {
            String queueId = tuple.getValue();
            if (queueId == null) { rank++; continue; }

            int position = rank + 1;
            QueueSnapshot snapshot = QueueSnapshot.waiting(
                    queueId, position, rank,
                    (long) Math.ceil(position / (double) Math.max(1, queueManagerService.getAdmitPerTick())) * 2,
                    status
            );
            messagingTemplate.convertAndSend("/topic/queue/" + queueId, snapshot);
            rank++;
        }
    }
}
