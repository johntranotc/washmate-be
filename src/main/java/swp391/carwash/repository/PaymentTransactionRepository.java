package swp391.carwash.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import swp391.carwash.entity.PaymentTransaction;
import swp391.carwash.enums.PaymentTransactionStatus;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Integer> {
    boolean existsByProviderAndProviderTxnId(String provider, String providerTxnId);

    Optional<PaymentTransaction> findByProviderAndProviderTxnId(String provider, String providerTxnId);

    Optional<PaymentTransaction> findByProviderAndMerchantTxnRef(String provider, String merchantTxnRef);

    List<PaymentTransaction> findByPaymentIdAndStatus(Integer paymentId, PaymentTransactionStatus status);

    List<PaymentTransaction> findByPaymentIdOrderByCreatedAtDesc(Integer paymentId);
}
