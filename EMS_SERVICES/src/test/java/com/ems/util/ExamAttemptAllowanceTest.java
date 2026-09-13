package com.ems.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ems.entity.CertificationApplication;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.PaymentStatus;

class ExamAttemptAllowanceTest {

	@Test
	void aFailedFirstAttemptOnAThreeAttemptPaymentHasTwoRetakesLeft() {
		CertificationApplication application = application(
				CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS, 1, 3);

		assertThat(ExamAttemptAllowance.attemptsRemaining(application)).isEqualTo(2);
		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isTrue();
	}

	@Test
	void aTerminatedAttemptIsRetakenOnTheSameTermsAsAFailedOne() {
		CertificationApplication application = application(
				CertificationApplicationStatus.TERMINATED, PaymentStatus.SUCCESS, 2, 3);

		assertThat(ExamAttemptAllowance.attemptsRemaining(application)).isEqualTo(1);
		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isTrue();
	}

	@Test
	void theLastAttemptOnAPaymentOffersNoRetake() {
		CertificationApplication application = application(
				CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS, 3, 3);

		assertThat(ExamAttemptAllowance.attemptsRemaining(application)).isZero();
		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isFalse();
	}

	/** Paid before attempts were recorded: that payment bought one sitting, whatever the policy says now. */
	@Test
	void aPaymentWithNoRecordedAllowanceCoversOneSitting() {
		CertificationApplication application = application(
				CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS, 1, null);

		assertThat(ExamAttemptAllowance.attemptsAllowed(application)).isEqualTo(1);
		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isFalse();
	}

	@Test
	void aRefundTakesTheUnusedAttemptsWithIt() {
		CertificationApplication application = application(
				CertificationApplicationStatus.FAILED, PaymentStatus.REFUNDED, 1, 3);

		assertThat(ExamAttemptAllowance.attemptsRemaining(application)).isZero();
		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isFalse();
	}

	@Test
	void aPassIsNeverOfferedARetake() {
		CertificationApplication application = application(
				CertificationApplicationStatus.PASSED, PaymentStatus.SUCCESS, 1, 3);

		assertThat(ExamAttemptAllowance.retakeAvailable(application)).isFalse();
	}

	@Test
	void aRetakeLeadsBackToTheApplicationThatWasPaidFor() {
		CertificationApplication paid = application(CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS, 1, 3);
		CertificationApplication retake = application(CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS, 2, 3);
		retake.setPaidApplication(paid);

		assertThat(ExamAttemptAllowance.paidApplication(retake)).isSameAs(paid);
		assertThat(ExamAttemptAllowance.paidApplication(paid)).isSameAs(paid);
	}

	private static CertificationApplication application(
			CertificationApplicationStatus status,
			PaymentStatus paymentStatus,
			int attemptNumber,
			Integer attemptsAllowed) {
		return CertificationApplication.builder()
				.applicationStatus(status)
				.paymentStatus(paymentStatus)
				.attemptNumber(attemptNumber)
				.attemptsAllowed(attemptsAllowed)
				.build();
	}
}
