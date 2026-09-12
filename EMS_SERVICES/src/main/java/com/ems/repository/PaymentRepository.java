package com.ems.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.entity.Payment;
import com.ems.entity.User;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTransactionId(String transactionId);

    Optional<Payment> findTopByCertificationApplicationIdOrderByCreatedDateDesc(
            Long certificationApplicationId);

    Optional<Payment> findByTransactionIdAndUser(String transactionId, User user);

    /**
     * Looks a payment up the way a gateway callback identifies it.
     *
     * <p>Webhooks know the gateway's order id, not our transaction id, and they
     * arrive unauthenticated by any user session -- so this lookup is deliberately
     * not scoped to a {@link User}.</p>
     */
    Optional<Payment> findByProviderOrderId(String providerOrderId);

    boolean existsByCertificationApplicationIdAndPaymentStatus(Long certificationApplicationId, PaymentStatus paymentStatus);

    List<Payment> findByUserOrderByCreatedDateDesc(User user);

    List<Payment> findAllByOrderByCreatedDateDesc();

    /**
     * The admin console's payment search. Every filter is optional, and
     * {@code searchText} is expected as a lower-cased LIKE pattern.
     *
     * <p>Candidate, exam and application are fetched in the same statement: the
     * console shows all three on every row, and loading them lazily costs up to
     * three further queries per payment.</p>
     *
     * <p>{@code from} is inclusive and {@code to} exclusive, both on the clock
     * {@code created_date} is written in. They are cast in their null checks for
     * the reason given on {@link AuditLogRepository#search}: PostgreSQL cannot
     * infer a type for a bare {@code ? is null}.</p>
     */
    @Query("""
            select p from Payment p
            join fetch p.user u
            join fetch p.exam e
            left join fetch p.certificationApplication a
            where (:status is null or p.paymentStatus = :status)
            and (:gatewayMode is null or p.gatewayMode = :gatewayMode)
            and (:paymentMethod is null or p.paymentMethod = :paymentMethod)
            and (:searchText is null
                 or lower(p.transactionId) like :searchText
                 or lower(p.providerOrderId) like :searchText
                 or lower(p.providerReference) like :searchText
                 or lower(u.userId) like :searchText
                 or lower(u.email) like :searchText
                 or lower(u.firstName) like :searchText
                 or lower(u.lastName) like :searchText
                 or lower(concat(u.firstName, ' ', u.lastName)) like :searchText)
            and (cast(:from as timestamp) is null or p.createdDate >= :from)
            and (cast(:to as timestamp) is null or p.createdDate < :to)
            order by p.createdDate desc, p.id desc
            """)
    List<Payment> searchForAdmin(
            @Param("searchText") String searchText,
            @Param("status") PaymentStatus status,
            @Param("gatewayMode") PaymentGatewayMode gatewayMode,
            @Param("paymentMethod") String paymentMethod,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
