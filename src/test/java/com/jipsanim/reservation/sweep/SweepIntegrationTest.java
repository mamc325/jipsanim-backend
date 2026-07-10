package com.jipsanim.reservation.sweep;

import com.jipsanim.TestcontainersConfiguration;
import com.jipsanim.property.domain.DealType;
import com.jipsanim.property.domain.Property;
import com.jipsanim.property.domain.PropertyType;
import com.jipsanim.property.repository.PropertyRepository;
import com.jipsanim.reservation.domain.Payment;
import com.jipsanim.reservation.domain.PaymentStatus;
import com.jipsanim.reservation.domain.Reservation;
import com.jipsanim.reservation.domain.ReservationStatus;
import com.jipsanim.reservation.dto.ReservationCreateResponse;
import com.jipsanim.reservation.queue.WaitingQueueService;
import com.jipsanim.reservation.repository.PaymentRepository;
import com.jipsanim.reservation.repository.ReservationRepository;
import com.jipsanim.reservation.service.PaymentService;
import com.jipsanim.reservation.service.ReservationService;
import com.jipsanim.reservation.slot.domain.VisitSlot;
import com.jipsanim.reservation.slot.repository.VisitSlotRepository;
import com.jipsanim.reservation.waiting.WaitingService;
import com.jipsanim.user.domain.Realtor;
import com.jipsanim.user.domain.Role;
import com.jipsanim.user.domain.User;
import com.jipsanim.user.repository.RealtorRepository;
import com.jipsanim.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SweepIntegrationTest {

    @Autowired
    SweepService sweepService;
    @Autowired
    ReservationService reservationService;
    @Autowired
    PaymentService paymentService;
    @Autowired
    WaitingService waitingService;
    @Autowired
    WaitingQueueService queue;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    VisitSlotRepository slotRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RealtorRepository realtorRepository;
    @Autowired
    PropertyRepository propertyRepository;
    @Autowired
    StringRedisTemplate redis;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private record SlotFixture(Long slotId, Long propertyId, Long realtorId) {
    }

    private SlotFixture openSlot() {
        int n = SEQ.incrementAndGet();
        User ru = userRepository.save(User.create("sweep.realtor" + n + "@test.com", "pw", "r", Role.REALTOR));
        Realtor realtor = realtorRepository.save(Realtor.create(ru, "공인", "010"));
        Property p = Property.createDraft(realtor, "매물", "설명설명설명설명설명설명", "서울 강남구 A로 1",
                "1168010100", "강남구", "역", PropertyType.OFFICETEL, DealType.MONTHLY_RENT,
                10_000_000L, 700_000L, new BigDecimal("33"), 1);
        p.approve();
        propertyRepository.save(p);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withNano(0);
        VisitSlot slot = slotRepository.save(VisitSlot.create(p, start, start.plusMinutes(30)));
        return new SlotFixture(slot.getId(), p.getId(), realtor.getId());
    }

    private Long userId(String email) {
        return userRepository.save(User.create(email, "pw", "u", Role.USER)).getId();
    }

    @Test
    @DisplayName("sweep: 만료 PENDING 정리 → 다음 대기자 발급 → 다음 예약 409 없음(P2)")
    void expireThenReissue() {
        SlotFixture f = openSlot();
        Long a = userId("sweep.a@test.com");
        Long b = userId("sweep.b@test.com");

        // A: 만료된 PENDING 예약(+READY 결제) 직접 시드
        Reservation aRes = reservationRepository.save(
                Reservation.create(a, f.propertyId(), f.slotId(), LocalDateTime.now().minusMinutes(1)));
        paymentRepository.save(Payment.create(aRes.getId(), a, f.realtorId(), 10000L));
        // B: 대기열 진입(토큰 없음)
        queue.enqueue(f.slotId(), b);

        sweepService.sweep();

        // A 만료 확정
        assertThat(reservationRepository.findById(aRes.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(paymentRepository.findByReservationId(aRes.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        // B 예약권 발급
        assertThat(queue.hasToken(f.slotId(), b)).isTrue();
        // B 예약 생성 → active_reservation_key 충돌 없이 성공(P2)
        ReservationCreateResponse resp = reservationService.create(b, f.slotId());
        assertThat(resp.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("sweep 은 CONFIRMED 예약을 만료시키지 않는다(만료 경계 상호배제, T262)")
    void sweepSkipsConfirmed() {
        SlotFixture f = openSlot();
        Long u = userId("sweep.confirm@test.com");

        waitingService.enter(f.slotId(), u);                  // 토큰
        ReservationCreateResponse resv = reservationService.create(u, f.slotId());
        paymentService.confirm(resv.paymentId(), u);          // CONFIRMED

        sweepService.sweep();

        assertThat(reservationRepository.findById(resv.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentRepository.findById(resv.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
    }
}
