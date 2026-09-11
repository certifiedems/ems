package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ems.config.AuditorAwareConfig;
import com.ems.config.JpaAuditingConfig;
import com.ems.dto.response.BulkQuestionUploadResponse;
import com.ems.entity.Question;
import com.ems.repository.QuestionRepository;
import com.ems.service.AuditService;
import com.ems.service.QuestionService;

/**
 * Runs bulk upload against a real database and real transactions, which the
 * mocked {@link QuestionServiceImplTest} cannot: a row the database refuses has
 * to fail on its own, not take the whole upload down with it.
 */
@DataJpaTest
@Import({ QuestionServiceImpl.class, JpaAuditingConfig.class, AuditorAwareConfig.class })
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
// Let the service open and commit its own transactions, as it does in a request.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuestionBulkUploadTransactionTest {

    private static final String REJECTED_TEXT = "Rejected by the database?";

    @MockBean
    private AuditService auditService;

    @Autowired
    private QuestionService questionService;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE questions DROP CONSTRAINT IF EXISTS chk_rejects_test_row");
        questionRepository.deleteAll();
    }

    @Test
    void rowRejectedByTheDatabaseFailsAloneAndTheOthersAreSaved() {
        // Stands in for any insert the database refuses after the row passed validation.
        jdbcTemplate.execute("ALTER TABLE questions ADD CONSTRAINT chk_rejects_test_row"
                + " CHECK (question_text <> '" + REJECTED_TEXT + "')");

        String csv = "quesID,question,option1,option2,option3,option4,answer,severity,category,marks,Level\n"
                + "Q001L,Which is B?,A,B,C,D,B,LOW,TECHNICAL,1,L1\n"
                + "Q002L," + REJECTED_TEXT + ",A,B,C,D,B,LOW,TECHNICAL,1,L1\n"
                + "Q003L,Which is C?,A,B,C,D,C,LOW,TECHNICAL,1,L1\n";

        BulkQuestionUploadResponse response = questionService.bulkUpload(
                new MockMultipartFile("file", "questions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.createdRows()).isEqualTo(2);
        assertThat(response.errors()).singleElement().asString()
                .startsWith("Row 3 (Q002L): ")
                .containsIgnoringCase("chk_rejects_test_row");
        assertThat(questionRepository.findAll())
                .extracting(Question::getQuestionCode)
                .containsExactlyInAnyOrder("Q001L", "Q003L");
    }
}
