package com.ems.service.impl;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ems.exception.BusinessException;
import com.ems.service.CertificatePdfGeneratorService;
import com.ems.service.CertificateTemplate;
import com.ems.service.CertificateTemplate.TextRun;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders the issued certificate as a landscape A4 PDF.
 *
 * <p>The artwork and every line of copy are produced here, server-side, from
 * the persisted certificate record and the level's {@link CertificateTemplate}.
 * Nothing about the document is supplied by the caller, so a client cannot
 * influence the award title, the level, or the competencies a certificate
 * claims — the only path to a valid PDF is a certificate row that the exam
 * workflow actually created.
 */
@Slf4j
@Service
public class PdfBoxCertificatePdfGeneratorService implements CertificatePdfGeneratorService {

	private static final float PAGE_W = PDRectangle.A4.getHeight();
	private static final float PAGE_H = PDRectangle.A4.getWidth();

	// Palette taken from the approved certificate artwork.
	private static final Color INK = new Color(0x14, 0x43, 0x2F);
	private static final Color MID_GREEN = new Color(0x2E, 0x7D, 0x5B);
	private static final Color RAIL = new Color(0x11, 0x45, 0x33);
	private static final Color RAIL_TRACE = new Color(0x2B, 0x6B, 0x51);
	private static final Color CHIP = new Color(0x1B, 0x33, 0x29);
	private static final Color GOLD = new Color(0xB8, 0x93, 0x3E);
	private static final Color GOLD_LIGHT = new Color(0xD6, 0xB6, 0x63);
	private static final Color MUTED = new Color(0x7C, 0x8C, 0x86);
	private static final Color FAINT = new Color(0xA8, 0xB5, 0xB0);
	private static final Color BODY = new Color(0x3F, 0x4A, 0x46);
	private static final Color MINT = new Color(0xE6, 0xF1, 0xEA);
	private static final Color CREAM = new Color(0xF6, 0xF1, 0xE3);
	private static final Color HAIRLINE = new Color(0xC9, 0xD3, 0xCE);

	// Layout anchors, all measured downwards from the top edge of the page.
	private static final float CONTENT_X = 112f;
	private static final float NAME_RULE_WIDTH = 385f;
	private static final float CITATION_WIDTH = 427f;
	private static final float CITATION_SIZE = 9.5f;
	/** Size of the rest of a word relative to its enlarged opening letter. */
	private static final float NAME_BODY_RATIO = 0.75f;
	private static final float NAME_SPACING = 0.4f;
	private static final float CHIP_CX = 700f;
	private static final float SEAL_CX = 752f;
	private static final float SEAL_CY = 498f;

	private static final DateTimeFormatter DATE_FORMAT =
			DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	@Override
	public byte[] generateCertificatePdf(CertificatePdfData data) {
		CertificateTemplate template = CertificateTemplate.forLevel(data.certificationLevel());

		try (PDDocument document = new PDDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
			document.addPage(page);

			applyMetadata(document, data, template);

			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				CertificateCanvas canvas = new CertificateCanvas(content, PAGE_H);

				drawBackdrop(canvas);
				drawFrame(canvas);
				drawLeftRail(canvas);
				drawHeader(canvas, data);
				drawAward(canvas, template);
				drawRecipient(canvas, data.candidateName());
				drawCitation(canvas, template);
				float competenciesBottom = drawCompetencies(canvas, template);
				drawCircuitry(canvas, competenciesBottom);
				drawLevelChip(canvas, template);
				drawSeal(canvas);
				drawQrCode(document, content, canvas, data.qrCodePng());
				drawSignatures(canvas, data.issueDate());
				drawFooter(canvas, data.verificationUrl());
			}

			document.save(output);
			return output.toByteArray();
		} catch (IOException ex) {
			log.error("Certificate PDF rendering failed for certificateNumber={}", data.certificateNumber(), ex);
			throw new BusinessException("Failed to generate certificate PDF", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private void applyMetadata(PDDocument document, CertificatePdfData data, CertificateTemplate template) {
		PDDocumentInformation info = document.getDocumentInformation();
		info.setTitle(template.awardTitle() + " - " + data.certificateNumber());
		info.setSubject(template.eyebrow());
		info.setAuthor("Certified EMS Engineers Board");
		info.setCreator("Examination Management System");
		info.setKeywords(data.certificateNumber() + " " + data.certificationLevel());

		// Lets a download tell a file rendered by this build apart from one left
		// behind by an earlier artwork revision.
		info.setCustomMetadataValue(DESIGN_VERSION_KEY, DESIGN_VERSION);
	}

	/* ------------------------------------------------------------------ */
	/* Background and structure                                            */
	/* ------------------------------------------------------------------ */

	private void drawBackdrop(CertificateCanvas canvas) throws IOException {
		canvas.fillRect(0, 0, PAGE_W, PAGE_H, Color.WHITE);

		// Soft shapes bleeding out of two corners; clipped so they never cross
		// the printed frame.
		canvas.saveState();
		canvas.clipRect(14, 14, PAGE_W - 28, PAGE_H - 28);
		canvas.circle(858f, -30f, 232f, MINT, null, 0f);
		canvas.circle(112f, 672f, 104f, CREAM, null, 0f);
		canvas.restoreState();
	}

	private void drawFrame(CertificateCanvas canvas) throws IOException {
		canvas.strokeRect(8f, 8f, PAGE_W - 16f, PAGE_H - 16f, 1.4f, INK);
		canvas.strokeRect(15f, 15f, PAGE_W - 30f, PAGE_H - 30f, 0.7f, GOLD);
	}

	/** Dark circuit-board rail down the left edge. */
	private void drawLeftRail(CertificateCanvas canvas) throws IOException {
		float x = 20f;
		float width = 62f;
		float top = 20f;
		float height = PAGE_H - 40f;

		canvas.fillRect(x, top, width, height, RAIL);

		// Vertical traces with stepped branches, echoing board routing.
		canvas.line(x + 18f, top + 24f, x + 18f, top + height - 24f, 0.7f, RAIL_TRACE);
		canvas.line(x + 31f, top + 40f, x + 31f, top + height - 40f, 0.5f, RAIL_TRACE);
		canvas.line(x + 44f, top + 60f, x + 44f, top + height - 90f, 0.7f, RAIL_TRACE);
		canvas.line(x + 18f, top + 150f, x + 44f, top + 176f, 0.7f, RAIL_TRACE);
		canvas.line(x + 44f, top + 300f, x + 31f, top + 313f, 0.5f, RAIL_TRACE);
		canvas.line(x + 18f, top + 420f, x + 31f, top + 433f, 0.5f, RAIL_TRACE);

		// Vias alternate between the two edges of the rail.
		float[] viaY = { 58f, 148f, 232f, 318f, 404f, 486f, 552f };
		for (int i = 0; i < viaY.length; i++) {
			float cx = (i % 2 == 0) ? x + 12f : x + 50f;
			canvas.circle(cx, viaY[i], 4.2f, null, GOLD, 1.1f);
			canvas.circle(cx, viaY[i], 1.5f, GOLD, null, 0f);
		}

		// Surface-mount components sitting on the traces.
		float[] smdY = { 104f, 196f, 288f, 372f, 462f, 528f };
		for (int i = 0; i < smdY.length; i++) {
			float cx = (i % 2 == 0) ? x + 14f : x + 40f;
			canvas.fillRect(cx, smdY[i], 12f, 6f, GOLD);
			canvas.fillRect(cx + 1.5f, smdY[i] + 1.5f, 9f, 3f, RAIL);
		}
	}

	/* ------------------------------------------------------------------ */
	/* Header                                                              */
	/* ------------------------------------------------------------------ */

	private void drawHeader(CertificateCanvas canvas, CertificatePdfData data) throws IOException {
		drawBrandMark(canvas, CONTENT_X, 38f, 32f);

		canvas.text(PDType1Font.HELVETICA_BOLD, 15f, 1.9f, INK, CONTENT_X + 44f, 60f,
				"CERTIFIED EMS ENGINEERS");
		canvas.text(PDType1Font.HELVETICA, 6.5f, 1.15f, MUTED, CONTENT_X + 45f, 74f,
				"WWW.CERTIFIEDEMSENGINEERS.COM");

		// Dotted flourish between the wordmark and the certificate number.
		for (int row = 0; row < 2; row++) {
			for (int col = 0; col < 6; col++) {
				canvas.circle(452f + col * 9f, 24f + row * 9f, 1.4f, GOLD_LIGHT, null, 0f);
			}
		}

		drawCertificateNumber(canvas, data.certificateNumber());
	}

	/** Chip-shaped logo with a tick in the middle. */
	private void drawBrandMark(CertificateCanvas canvas, float x, float yTop, float size) throws IOException {
		for (int i = 0; i < 3; i++) {
			float offset = size * (0.24f + i * 0.26f);
			canvas.fillRect(x + offset, yTop - 4f, 4f, 5f, GOLD);
			canvas.fillRect(x + offset, yTop + size - 1f, 4f, 5f, GOLD);
			canvas.fillRect(x - 4f, yTop + offset, 5f, 4f, GOLD);
			canvas.fillRect(x + size - 1f, yTop + offset, 5f, 4f, GOLD);
		}
		canvas.roundedRect(x, yTop, size, size, 5f, INK, null, 0f);

		// Tick, drawn as two strokes.
		canvas.line(x + size * 0.28f, yTop + size * 0.52f, x + size * 0.44f, yTop + size * 0.68f, 2.6f, GOLD_LIGHT);
		canvas.line(x + size * 0.44f, yTop + size * 0.68f, x + size * 0.74f, yTop + size * 0.32f, 2.6f, GOLD_LIGHT);
	}

	private void drawCertificateNumber(CertificateCanvas canvas, String certificateNumber) throws IOException {
		float boxX = 666f;
		float boxW = 146f;
		canvas.strokeRect(boxX, 32f, boxW, 44f, 0.7f, HAIRLINE);
		canvas.rightText(PDType1Font.HELVETICA, 6.5f, 1.2f, MUTED, boxX + boxW - 9f, 48f, "CERTIFICATE NO.");

		// The number is the anchor for verification, so it is auto-fitted rather
		// than allowed to spill outside its box.
		float size = 9.5f;
		while (size > 6f
				&& CertificateCanvas.textWidth(PDType1Font.HELVETICA_BOLD, size, 0.4f, certificateNumber) > boxW - 18f) {
			size -= 0.25f;
		}
		canvas.rightText(PDType1Font.HELVETICA_BOLD, size, 0.4f, INK, boxX + boxW - 9f, 66f, certificateNumber);
	}

	/* ------------------------------------------------------------------ */
	/* Award block                                                         */
	/* ------------------------------------------------------------------ */

	private void drawAward(CertificateCanvas canvas, CertificateTemplate template) throws IOException {
		canvas.text(PDType1Font.HELVETICA_BOLD, 9f, 2.4f, GOLD, CONTENT_X, 126f, template.eyebrow());
		canvas.text(PDType1Font.HELVETICA_BOLD, 30f, 0f, INK, CONTENT_X, 160f, template.titleLineOne());
		canvas.text(PDType1Font.HELVETICA, 30f, 0f, MID_GREEN, CONTENT_X, 192f, template.titleLineTwo());
		canvas.rule(CONTENT_X, 200f, 180f, 4f, GOLD);
		canvas.text(PDType1Font.HELVETICA, 8f, 2.2f, MUTED, CONTENT_X, 219f, template.tierLine());
		canvas.text(PDType1Font.HELVETICA, 9.5f, 2.6f, MUTED, CONTENT_X, 249f, "THIS IS TO CERTIFY THAT");
	}

	/**
	 * Draws the recipient name above its rule.
	 *
	 * <p>The name is the one piece of free-form data on the page, so it gets a
	 * fitting pass rather than a fixed size: the bold face shrinks first, then
	 * the lighter regular face takes over because it is appreciably narrower at
	 * the same size, and only a name too long for even that is truncated.
	 *
	 * <p>Each word opens with an enlarged initial, so the fitting pass measures
	 * the name as it is actually drawn rather than at one flat size.
	 */
	private void drawRecipient(CertificateCanvas canvas, String candidateName) throws IOException {
		float maxWidth = NAME_RULE_WIDTH - 8f;
		String name = displayName(candidateName);

		PDFont font = PDType1Font.HELVETICA_BOLD;
		float size = 26f;
		while (size > 18f && nameWidth(font, size, name) > maxWidth) {
			size -= 0.5f;
		}
		if (nameWidth(font, size, name) > maxWidth) {
			font = PDType1Font.HELVETICA;
			while (size > 11f && nameWidth(font, size, name) > maxWidth) {
				size -= 0.5f;
			}
		}
		if (nameWidth(font, size, name) > maxWidth) {
			name = truncateName(font, size, name, maxWidth);
			log.warn("Candidate name truncated on certificate: originalLength={}", candidateName.length());
		}

		drawName(canvas, font, size, CONTENT_X + 2f, 300f, name);
		canvas.rule(CONTENT_X, 308f, NAME_RULE_WIDTH, 0.9f, HAIRLINE);
	}

	/**
	 * Restores the capitalisation of a name that was typed without it.
	 *
	 * <p>Registration takes the name as given, so a candidate who typed their
	 * surname in lower case had it engraved that way. Only words with no capital
	 * of their own are touched: anything the candidate deliberately cased —
	 * "McDonald", "O'Brien", a numeral suffix like "III" — is left exactly as it
	 * was typed, since second-guessing it does more damage than the lower-case
	 * surname it would fix.
	 */
	static String displayName(String candidateName) {
		if (candidateName == null) {
			return "";
		}
		String[] words = candidateName.trim().split("\\s+");
		StringBuilder out = new StringBuilder(candidateName.length());
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(capitalize(word));
		}
		return out.toString();
	}

	/** Capitalises a word typed in lower case, including after any hyphen. */
	private static String capitalize(String word) {
		if (word.chars().anyMatch(Character::isUpperCase)) {
			return word;
		}
		StringBuilder out = new StringBuilder(word);
		boolean atBoundary = true;
		for (int i = 0; i < out.length(); i++) {
			char character = out.charAt(i);
			if (atBoundary && Character.isLetter(character)) {
				out.setCharAt(i, Character.toUpperCase(character));
				atBoundary = false;
			} else if (character == '-') {
				atBoundary = true;
			}
		}
		return out.toString();
	}

	/** True where a character opens a word and is therefore drawn enlarged. */
	private static boolean isInitial(String name, int index) {
		return index == 0 || Character.isWhitespace(name.charAt(index - 1));
	}

	private static float sizeAt(String name, int index, float size) {
		return isInitial(name, index) ? size : size * NAME_BODY_RATIO;
	}

	/**
	 * Width of the name as drawn, summed per character because the enlarged
	 * initials mean no single font size describes the whole string.
	 */
	private static float nameWidth(PDFont font, float size, String name) {
		float total = 0f;
		for (int i = 0; i < name.length(); i++) {
			total += CertificateCanvas.textWidth(font, sizeAt(name, i, size), 0f,
					String.valueOf(name.charAt(i)));
		}
		return total + NAME_SPACING * Math.max(0, name.length() - 1);
	}

	/** Draws the name as runs of uniform size, one enlarged initial per word. */
	private void drawName(CertificateCanvas canvas, PDFont font, float size, float x, float yTop, String name)
			throws IOException {
		int index = 0;
		float cursor = x;
		while (index < name.length()) {
			float runSize = sizeAt(name, index, size);
			int start = index++;
			while (index < name.length() && sizeAt(name, index, size) == runSize) {
				index++;
			}
			String run = name.substring(start, index);
			canvas.text(font, runSize, NAME_SPACING, INK, cursor, yTop, run);
			// Plus one spacing for the gap the run's own width leaves off at its end.
			cursor += CertificateCanvas.textWidth(font, runSize, NAME_SPACING, run) + NAME_SPACING;
		}
	}

	private String truncateName(PDFont font, float size, String value, float maxWidth) {
		String ellipsis = "...";
		String candidate = value;
		while (candidate.length() > 1 && nameWidth(font, size, candidate + ellipsis) > maxWidth) {
			candidate = candidate.substring(0, candidate.length() - 1);
		}
		return candidate.stripTrailing() + ellipsis;
	}

	private void drawCitation(CertificateCanvas canvas, CertificateTemplate template) throws IOException {
		List<List<Word>> lines = wrapCitation(template.citation(), CITATION_SIZE, CITATION_WIDTH);
		float yTop = 336f;
		for (List<Word> line : lines) {
			float x = CONTENT_X;
			for (Word word : line) {
				for (Segment segment : word.segments()) {
					PDFont font = fontFor(segment.bold());
					Color color = segment.bold() ? INK : BODY;
					canvas.text(font, CITATION_SIZE, 0f, color, x, yTop, segment.text());
					x += CertificateCanvas.textWidth(font, CITATION_SIZE, 0f, segment.text());
				}
			}
			yTop += 15f;
		}
	}

	/** A stretch of one word rendered in a single face. */
	private record Segment(String text, boolean bold) {
	}

	/**
	 * A word plus any trailing spaces. It holds segments rather than a single
	 * emphasis flag because punctuation can change face mid-word: the comma
	 * after a bold phrase belongs to the regular run that follows it.
	 */
	private record Word(List<Segment> segments) {
	}

	private static PDFont fontFor(boolean bold) {
		return bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
	}

	private static float wordWidth(Word word, float fontSize) {
		float total = 0f;
		for (Segment segment : word.segments()) {
			total += CertificateCanvas.textWidth(fontFor(segment.bold()), fontSize, 0f, segment.text());
		}
		return total;
	}

	/**
	 * Greedy word wrap across a paragraph of mixed regular and bold text.
	 *
	 * <p>Emphasis boundaries sit inside the paragraph and do not line up with
	 * word boundaries, so the runs are first flattened into a character stream
	 * carrying a per-character face. Splitting each run on spaces independently
	 * would lose the space wherever one run ends on a word and the next begins
	 * with one.
	 */
	private List<List<Word>> wrapCitation(List<TextRun> runs, float fontSize, float maxWidth) {
		StringBuilder characters = new StringBuilder();
		List<Boolean> bold = new ArrayList<>();
		for (TextRun run : runs) {
			for (char character : run.text().toCharArray()) {
				characters.append(character);
				bold.add(run.bold());
			}
		}

		List<Word> words = new ArrayList<>();
		int index = 0;
		while (index < characters.length()) {
			int wordStart = index;
			while (index < characters.length() && characters.charAt(index) != ' ') {
				index++;
			}
			int wordEnd = index;
			// Trailing spaces ride along with the word they follow, so a line
			// break never leaves a dangling space at the start of the next line.
			while (index < characters.length() && characters.charAt(index) == ' ') {
				index++;
			}
			if (wordEnd == wordStart) {
				continue;
			}
			words.add(new Word(toSegments(characters, bold, wordStart, index)));
		}

		List<List<Word>> lines = new ArrayList<>();
		List<Word> current = new ArrayList<>();
		float lineWidth = 0f;
		for (Word word : words) {
			float width = wordWidth(word, fontSize);
			if (!current.isEmpty() && lineWidth + width > maxWidth) {
				lines.add(current);
				current = new ArrayList<>();
				lineWidth = 0f;
			}
			current.add(word);
			lineWidth += width;
		}
		if (!current.isEmpty()) {
			lines.add(current);
		}
		return lines;
	}

	/** Groups [start, end) into the fewest segments of uniform emphasis. */
	private List<Segment> toSegments(CharSequence characters, List<Boolean> bold, int start, int end) {
		List<Segment> segments = new ArrayList<>();
		int segmentStart = start;
		for (int i = start + 1; i <= end; i++) {
			if (i == end || !bold.get(i).equals(bold.get(segmentStart))) {
				segments.add(new Segment(characters.subSequence(segmentStart, i).toString(), bold.get(segmentStart)));
				segmentStart = i;
			}
		}
		return segments;
	}

	/**
	 * Competency chips, wrapped across as many rows as they need.
	 *
	 * @return the y coordinate just below the last row, so the decorative traces
	 *         can be placed relative to a block whose height depends on how many
	 *         competencies the level defines
	 */
	private float drawCompetencies(CertificateCanvas canvas, CertificateTemplate template) throws IOException {
		float maxRowWidth = 380f;
		float height = 17f;
		float gapX = 8f;
		float gapY = 7f;
		float x = CONTENT_X;
		float yTop = 392f;

		for (String label : template.competencies()) {
			float textWidth = CertificateCanvas.textWidth(PDType1Font.HELVETICA, 7f, 1f, label);
			float width = textWidth + 20f;

			if (x > CONTENT_X && x + width > CONTENT_X + maxRowWidth) {
				x = CONTENT_X;
				yTop += height + gapY;
			}

			canvas.strokeRect(x, yTop, width, height, 0.6f, HAIRLINE);
			canvas.fillRect(x, yTop, 2.6f, height, GOLD);
			canvas.text(PDType1Font.HELVETICA, 7f, 1f, INK, x + 11f, yTop + 11.5f, label);
			x += width + gapX;
		}
		return yTop + height;
	}

	/* ------------------------------------------------------------------ */
	/* Right-hand graphics                                                 */
	/* ------------------------------------------------------------------ */

	/**
	 * Decorative board traces linking the chip to the lower half of the page.
	 *
	 * @param competenciesBottom bottom of the competency block, so the long
	 *                           traces always run clear of it
	 */
	private void drawCircuitry(CertificateCanvas canvas, float competenciesBottom) throws IOException {
		canvas.line(750f, 214f, 792f, 214f, 0.6f, HAIRLINE);
		canvas.line(792f, 214f, 800f, 222f, 0.6f, HAIRLINE);
		canvas.line(750f, 238f, 806f, 238f, 0.6f, HAIRLINE);
		canvas.line(806f, 238f, 806f, 268f, 0.6f, HAIRLINE);

		canvas.line(786f, 380f, 812f, 380f, 0.6f, HAIRLINE);
		canvas.line(786f, 380f, 776f, 390f, 0.6f, HAIRLINE);
		canvas.fillRect(779f, 377f, 11f, 6f, CHIP);
		canvas.circle(812f, 424f, 3.6f, null, GOLD, 1f);
		canvas.line(812f, 380f, 812f, 420f, 0.6f, HAIRLINE);

		// Two long traces below the competency chips, mirroring the routing on
		// the left rail.
		float upper = competenciesBottom + 22f;
		float lower = upper + 26f;

		canvas.line(210f, upper, 330f, upper, 0.6f, HAIRLINE);
		canvas.line(330f, upper, 346f, upper - 14f, 0.6f, HAIRLINE);
		canvas.line(346f, upper - 14f, 690f, upper - 14f, 0.6f, HAIRLINE);
		canvas.circle(492f, upper - 14f, 3.6f, null, GOLD, 1f);
		canvas.fillRect(238f, upper - 3f, 12f, 6f, CHIP);

		canvas.line(126f, lower, 452f, lower, 0.6f, HAIRLINE);
		canvas.circle(456f, lower, 3.6f, null, GOLD, 1f);
		canvas.fillRect(360f, lower - 3f, 12f, 6f, CHIP);
	}

	/** The level chip and the "n of 3" progress marker beneath it. */
	private void drawLevelChip(CertificateCanvas canvas, CertificateTemplate template) throws IOException {
		float bodyW = 92f;
		float bodyH = 98f;
		float x = CHIP_CX - bodyW / 2f;
		float yTop = 190f;

		// Fine leads out of the top edge.
		for (int i = 0; i < 3; i++) {
			float lx = x + 26f + i * 20f;
			canvas.line(lx, yTop - 16f, lx, yTop, 1.1f, HAIRLINE);
		}
		// Gold pins on the remaining three edges.
		for (int i = 0; i < 5; i++) {
			float py = yTop + 12f + i * 18f;
			canvas.fillRect(x - 11f, py, 12f, 7f, GOLD);
			canvas.fillRect(x + bodyW - 1f, py, 12f, 7f, GOLD);
			float px = x + 8f + i * 18f;
			canvas.fillRect(px, yTop + bodyH - 1f, 7f, 12f, GOLD);
		}

		canvas.roundedRect(x, yTop, bodyW, bodyH, 9f, CHIP, null, 0f);
		canvas.circle(x + 12f, yTop + 12f, 3.2f, GOLD, null, 0f);

		canvas.centeredText(PDType1Font.HELVETICA_BOLD, 40f, 0f, GOLD_LIGHT, CHIP_CX, yTop + 62f,
				"L" + template.levelIndex());
		canvas.centeredText(PDType1Font.HELVETICA, 6.5f, 1.8f, FAINT, CHIP_CX, yTop + 80f, template.chipCaption());

		// Progress squares: filled up to the level that was earned.
		float squareSize = 9f;
		float gap = 6f;
		float totalWidth = CertificateTemplate.TOTAL_LEVELS * squareSize
				+ (CertificateTemplate.TOTAL_LEVELS - 1) * gap;
		float startX = CHIP_CX - totalWidth / 2f;
		for (int i = 0; i < CertificateTemplate.TOTAL_LEVELS; i++) {
			float sx = startX + i * (squareSize + gap);
			if (i < template.levelIndex()) {
				canvas.fillRect(sx, 318f, squareSize, squareSize, GOLD);
			} else {
				canvas.strokeRect(sx, 318f, squareSize, squareSize, 0.9f, FAINT);
			}
		}
		canvas.centeredText(PDType1Font.HELVETICA, 7.5f, 1.6f, MUTED, CHIP_CX, 344f,
				"LEVEL " + template.levelIndex() + " OF " + CertificateTemplate.TOTAL_LEVELS);
	}

	/** Board seal: gold ring, arc lettering, chip-and-tick centre. */
	private void drawSeal(CertificateCanvas canvas) throws IOException {
		canvas.circle(SEAL_CX, SEAL_CY, 42f, Color.WHITE, GOLD, 2.2f);
		canvas.circle(SEAL_CX, SEAL_CY, 37f, null, GOLD_LIGHT, 0.7f);
		canvas.circle(SEAL_CX, SEAL_CY, 27f, INK, null, 0f);

		// Notches around the ring.
		for (int i = 0; i < 12; i++) {
			double angle = Math.toRadians(i * 30.0);
			float px = SEAL_CX + (float) Math.cos(angle) * 40f;
			float py = SEAL_CY - (float) Math.sin(angle) * 40f;
			canvas.fillRect(px - 1.6f, py - 1.6f, 3.2f, 3.2f, GOLD);
		}

		canvas.arcText(PDType1Font.HELVETICA_BOLD, 7f, INK, SEAL_CX, SEAL_CY, 32f, 148f, -116f, false, "CERTIFIED");
		canvas.arcText(PDType1Font.HELVETICA_BOLD, 7f, INK, SEAL_CX, SEAL_CY, 31f, -145f, 110f, true, "EMS BOARD");

		canvas.roundedRect(SEAL_CX - 12f, SEAL_CY - 12f, 24f, 24f, 4f, Color.WHITE, null, 0f);
		canvas.line(SEAL_CX - 6.5f, SEAL_CY + 1f, SEAL_CX - 1.5f, SEAL_CY + 6f, 2.4f, INK);
		canvas.line(SEAL_CX - 1.5f, SEAL_CY + 6f, SEAL_CX + 7f, SEAL_CY - 5.5f, 2.4f, INK);
	}

	private void drawQrCode(PDDocument document, PDPageContentStream content, CertificateCanvas canvas,
			byte[] qrCodePng) throws IOException {
		if (qrCodePng == null || qrCodePng.length == 0) {
			return;
		}
		BufferedImage qrImage = ImageIO.read(new ByteArrayInputStream(qrCodePng));
		if (qrImage == null) {
			log.warn("Certificate QR code payload could not be decoded; skipping QR block");
			return;
		}
		float size = 46f;
		float x = 766f;
		float yTop = 88f;

		// White backing keeps the code scannable over the tinted corner shape.
		canvas.fillRect(x - 4f, yTop - 4f, size + 8f, size + 8f, Color.WHITE);
		canvas.strokeRect(x - 4f, yTop - 4f, size + 8f, size + 8f, 0.6f, HAIRLINE);

		PDImageXObject qrObject = LosslessFactory.createFromImage(document, qrImage);
		content.drawImage(qrObject, x, canvas.y(yTop + size), size, size);
	}

	/* ------------------------------------------------------------------ */
	/* Footer                                                              */
	/* ------------------------------------------------------------------ */

	private void drawSignatures(CertificateCanvas canvas, LocalDate issueDate) throws IOException {
		float lineY = 538f;
		float labelY = 550f;
		float[] starts = { CONTENT_X, 322f, 532f };
		float[] widths = { 170f, 170f, 140f };
		String[] labels = { "DIRECTOR OF CERTIFICATION", "HEAD OF TECHNICAL BOARD", "DATE OF ISSUE" };

		if (issueDate != null) {
			canvas.text(PDType1Font.HELVETICA_BOLD, 10f, 0.3f, INK, starts[2] + 2f, lineY - 6f,
					issueDate.format(DATE_FORMAT));
		}

		for (int i = 0; i < labels.length; i++) {
			canvas.rule(starts[i], lineY, widths[i], 0.9f, INK);
			canvas.text(PDType1Font.HELVETICA, 8f, 1.5f, BODY, starts[i] + 6f, labelY + 8f, labels[i]);
		}
	}

	private void drawFooter(CertificateCanvas canvas, String verificationUrl) throws IOException {
		canvas.text(PDType1Font.HELVETICA, 7.5f, 1.2f, FAINT, CONTENT_X, 572f,
				"VERIFY THIS CREDENTIAL AT " + verificationUrl.toUpperCase(Locale.ROOT));

		// Wave flourish in the bottom-right corner.
		for (int i = 0; i < 2; i++) {
			float baseY = 556f + i * 7f;
			float x0 = 690f;
			float width = 118f;
			float amplitude = 5f;
			float previousX = x0;
			float previousY = baseY;
			for (int step = 1; step <= 24; step++) {
				float t = step / 24f;
				float px = x0 + width * t;
				float py = baseY - (float) Math.sin(t * Math.PI * 2) * amplitude;
				canvas.line(previousX, previousY, px, py, 0.7f, GOLD_LIGHT);
				previousX = px;
				previousY = py;
			}
		}
	}
}
