package com.ems.service.impl;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ems.enums.PaymentStatus;
import com.ems.exception.BusinessException;
import com.ems.service.PaymentReceiptPdfGeneratorService;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders a payment receipt as a portrait A4 PDF.
 *
 * <p>A receipt is a financial record, so every figure on the page comes from the
 * persisted payment row. The status is printed rather than assumed: a refunded
 * or failed payment produces a receipt that says so, instead of one that reads
 * like a valid proof of purchase.
 */
@Slf4j
@Service
public class PdfBoxPaymentReceiptPdfGeneratorService implements PaymentReceiptPdfGeneratorService {

	private static final float PAGE_W = PDRectangle.A4.getWidth();
	private static final float PAGE_H = PDRectangle.A4.getHeight();
	private static final float MARGIN = 56f;
	private static final float CONTENT_W = PAGE_W - MARGIN * 2f;

	// Print palette, matching the certificate artwork.
	private static final Color INK = new Color(0x14, 0x43, 0x2F);
	private static final Color BODY = new Color(0x3F, 0x4A, 0x46);
	private static final Color MUTED = new Color(0x7C, 0x8C, 0x86);
	private static final Color FAINT = new Color(0xA8, 0xB5, 0xB0);
	private static final Color GOLD = new Color(0xB8, 0x93, 0x3E);
	private static final Color GOLD_LIGHT = new Color(0xD6, 0xB6, 0x63);
	private static final Color CREAM = new Color(0xF6, 0xF1, 0xE3);
	private static final Color MINT = new Color(0xE6, 0xF1, 0xEA);
	private static final Color HAIRLINE = new Color(0xC9, 0xD3, 0xCE);
	private static final Color DANGER = new Color(0xA3, 0x3A, 0x3A);

	private static final DateTimeFormatter DATE_FORMAT =
			DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

	@Override
	public byte[] generateReceiptPdf(PaymentReceiptData data) {
		try (PDDocument document = new PDDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
			document.addPage(page);

			applyMetadata(document, data);

			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				CertificateCanvas canvas = new CertificateCanvas(content, PAGE_H);

				drawHeader(canvas);
				drawSummary(canvas, data);
				drawPayer(canvas, data);
				drawLineItem(canvas, data);
				drawPaymentDetails(canvas, data);
				drawFooter(canvas, data);
			}

			document.save(output);
			return output.toByteArray();
		} catch (IOException ex) {
			log.error("Failed to render payment receipt for transaction {}", data.transactionId(), ex);
			throw new BusinessException("Unable to generate payment receipt", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private void applyMetadata(PDDocument document, PaymentReceiptData data) {
		PDDocumentInformation info = document.getDocumentInformation();
		info.setTitle("Payment Receipt " + data.transactionId());
		info.setAuthor("Certified EMS Engineers Board");
		info.setSubject(data.description());
		info.setCreator("Certified EMS Engineers Board");
		document.setDocumentInformation(info);
	}

	/** Board masthead on a solid band across the top of the page. */
	private void drawHeader(CertificateCanvas canvas) throws IOException {
		canvas.fillRect(0f, 0f, PAGE_W, 96f, INK);
		canvas.fillRect(0f, 96f, PAGE_W, 3f, GOLD);

		canvas.roundedRect(MARGIN, 34f, 26f, 30f, 5f, CREAM, null, 0f);
		canvas.line(MARGIN + 7f, 50f, MARGIN + 11.5f, 55f, 2.4f, INK);
		canvas.line(MARGIN + 11.5f, 55f, MARGIN + 19f, 43f, 2.4f, INK);

		canvas.text(PDType1Font.HELVETICA_BOLD, 13f, 1.6f, CREAM, MARGIN + 38f, 47f, "CERTIFIED EMS");
		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.5f, GOLD_LIGHT, MARGIN + 39f, 60f, "ENGINEERS BOARD");

		canvas.rightText(PDType1Font.HELVETICA_BOLD, 12f, 2.4f, GOLD_LIGHT, PAGE_W - MARGIN, 48f, "PAYMENT RECEIPT");
		canvas.rightText(PDType1Font.HELVETICA, 6.5f, 1.2f, FAINT, PAGE_W - MARGIN, 62f,
				"OFFICIAL RECORD OF PAYMENT");
	}

	/** Receipt number, date and the status badge. */
	private void drawSummary(CertificateCanvas canvas, PaymentReceiptData data) throws IOException {
		float top = 138f;

		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, MARGIN, top, "RECEIPT NO.");
		canvas.text(PDType1Font.HELVETICA_BOLD, 12f, 0.4f, INK, MARGIN, top + 17f, data.transactionId());

		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, MARGIN, top + 42f, "DATE OF PAYMENT");
		canvas.text(PDType1Font.HELVETICA, 10f, 0f, BODY, MARGIN, top + 58f, formatDate(data.paymentDate()));

		drawStatusBadge(canvas, data.paymentStatus(), top);
	}

	/**
	 * Status badge, right aligned against the summary block.
	 *
	 * <p>Only a settled payment gets the affirmative green treatment; anything
	 * else is coloured to stop the page reading as proof of a completed purchase.
	 */
	private void drawStatusBadge(CertificateCanvas canvas, PaymentStatus status, float top) throws IOException {
		PaymentStatus resolved = status == null ? PaymentStatus.PENDING : status;
		Color accent = switch (resolved) {
			case SUCCESS -> INK;
			case REFUNDED -> GOLD;
			case FAILED -> DANGER;
			case PENDING -> MUTED;
		};
		Color fill = resolved == PaymentStatus.SUCCESS ? MINT : CREAM;
		String label = resolved == PaymentStatus.SUCCESS ? "PAID" : resolved.name();

		float badgeW = Math.max(96f, CertificateCanvas.textWidth(PDType1Font.HELVETICA_BOLD, 11f, 1.6f, label) + 34f);
		float badgeX = PAGE_W - MARGIN - badgeW;

		canvas.roundedRect(badgeX, top - 6f, badgeW, 34f, 6f, fill, accent, 1.1f);
		canvas.centeredText(PDType1Font.HELVETICA_BOLD, 11f, 1.6f, accent, badgeX + badgeW / 2f, top + 16f, label);
	}

	/** Who the receipt was issued to. */
	private void drawPayer(CertificateCanvas canvas, PaymentReceiptData data) throws IOException {
		float top = 236f;
		canvas.rule(MARGIN, top, CONTENT_W, 0.8f, HAIRLINE);

		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, MARGIN, top + 22f, "ISSUED TO");
		canvas.text(PDType1Font.HELVETICA_BOLD, 13f, 0f, INK, MARGIN, top + 41f, data.payerName());
		canvas.text(PDType1Font.HELVETICA, 9.5f, 0f, BODY, MARGIN, top + 57f, data.email());
		canvas.text(PDType1Font.HELVETICA, 9.5f, 0f, MUTED, MARGIN, top + 72f, "Candidate ID  " + data.userId());
	}

	/** The single charged line and its total. */
	private void drawLineItem(CertificateCanvas canvas, PaymentReceiptData data) throws IOException {
		float top = 344f;

		canvas.fillRect(MARGIN, top, CONTENT_W, 26f, MINT);
		canvas.text(PDType1Font.HELVETICA_BOLD, 7f, 1.4f, INK, MARGIN + 14f, top + 17f, "DESCRIPTION");
		canvas.rightText(PDType1Font.HELVETICA_BOLD, 7f, 1.4f, INK, PAGE_W - MARGIN - 14f, top + 17f, "AMOUNT");

		float rowTop = top + 26f;
		canvas.text(PDType1Font.HELVETICA, 10.5f, 0f, BODY, MARGIN + 14f, rowTop + 24f, data.description());
		canvas.rightText(PDType1Font.HELVETICA, 10.5f, 0f, BODY, PAGE_W - MARGIN - 14f, rowTop + 24f,
				formatAmount(data.amount(), data.currency()));
		canvas.rule(MARGIN, rowTop + 38f, CONTENT_W, 0.8f, HAIRLINE);

		float totalTop = rowTop + 38f;
		canvas.text(PDType1Font.HELVETICA_BOLD, 9f, 1.2f, INK, MARGIN + 14f, totalTop + 26f, "TOTAL");
		canvas.rightText(PDType1Font.HELVETICA_BOLD, 14f, 0f, INK, PAGE_W - MARGIN - 14f, totalTop + 28f,
				formatAmount(data.amount(), data.currency()));
		canvas.rule(MARGIN, totalTop + 40f, CONTENT_W, 1.6f, INK);
	}

	/** Provider and reference, for reconciliation against a bank statement. */
	private void drawPaymentDetails(CertificateCanvas canvas, PaymentReceiptData data) throws IOException {
		float top = 486f;
		float columnW = CONTENT_W / 2f;

		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, MARGIN, top, "PAYMENT METHOD");
		canvas.text(PDType1Font.HELVETICA, 10f, 0f, BODY, MARGIN, top + 17f, blankToDash(data.provider()));

		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, MARGIN + columnW, top, "PROVIDER REFERENCE");
		canvas.text(PDType1Font.HELVETICA, 10f, 0f, BODY, MARGIN + columnW, top + 17f,
				blankToDash(data.providerReference()));
	}

	private void drawFooter(CertificateCanvas canvas, PaymentReceiptData data) throws IOException {
		float top = PAGE_H - 108f;
		canvas.rule(MARGIN, top, CONTENT_W, 0.8f, HAIRLINE);

		canvas.text(PDType1Font.HELVETICA, 8.5f, 0f, MUTED, MARGIN, top + 22f,
				"This receipt is generated electronically and is valid without a signature.");
		canvas.text(PDType1Font.HELVETICA, 8.5f, 0f, MUTED, MARGIN, top + 37f,
				"For queries quote receipt number " + data.transactionId() + ".");
		canvas.rightText(PDType1Font.HELVETICA, 7f, 1.1f, FAINT, PAGE_W - MARGIN, top + 37f,
				"GENERATED " + formatDate(Instant.now()).toUpperCase(Locale.ENGLISH));
	}

	private String formatDate(Instant instant) {
		return instant == null ? "—" : DATE_FORMAT.format(instant);
	}

	private String blankToDash(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

	private String formatAmount(BigDecimal amount, String currency) {
		if (amount == null) {
			return "—";
		}
		String figure = String.format(Locale.ENGLISH, "%,.2f", amount);
		return currency == null || currency.isBlank() ? figure : currency + " " + figure;
	}
}
