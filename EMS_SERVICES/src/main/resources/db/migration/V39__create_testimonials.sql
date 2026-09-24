-- src/main/resources/db/migration/V39__create_testimonials.sql
-- Quotes certified candidates give about the certification, shown on the
-- public sign-in screen once an administrator approves them.
--
-- One per candidate per level. The row belongs to the certification that made
-- the candidate eligible, so a revoked certification takes its quote off the
-- site without anyone having to remember to.
--
-- quote is exactly what the candidate wrote and is never changed. An
-- administrator's typo fix goes in edited_quote, which the site shows when set;
-- the original stays so the two can always be compared.
--
-- consent_given_at / consent_version record the candidate agreeing to
-- publication. Withdrawing deletes the row outright rather than flagging it:
-- once consent is withdrawn there is no reason to keep the quote.
CREATE TABLE IF NOT EXISTS testimonials (
    id                  BIGSERIAL    PRIMARY KEY,
    user_ref            BIGINT       NOT NULL,
    certification_ref   BIGINT       NOT NULL,
    certification_level VARCHAR(10)  NOT NULL,
    rating              INT          NOT NULL,
    quote               VARCHAR(300) NOT NULL,
    edited_quote        VARCHAR(300),
    name_display        VARCHAR(30)  NOT NULL,
    job_title           VARCHAR(100),
    company             VARCHAR(100),
    status              VARCHAR(20)  NOT NULL,
    review_note         VARCHAR(300),
    reviewed_by         VARCHAR(100),
    reviewed_at         TIMESTAMPTZ,
    consent_given_at    TIMESTAMPTZ  NOT NULL,
    consent_version     VARCHAR(20)  NOT NULL,
    created_by          VARCHAR(100) NOT NULL,
    created_date        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100),
    updated_date        TIMESTAMP,
    CONSTRAINT uq_testimonials_user_level UNIQUE (user_ref, certification_level),
    CONSTRAINT fk_testimonials_user
        FOREIGN KEY (user_ref) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_testimonials_certification
        FOREIGN KEY (certification_ref) REFERENCES certifications (id) ON DELETE CASCADE,
    CONSTRAINT chk_testimonials_level CHECK (certification_level IN ('L1', 'L2', 'L3')),
    CONSTRAINT chk_testimonials_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_testimonials_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_testimonials_name_display
        CHECK (name_display IN ('FULL_NAME', 'FIRST_NAME_INITIAL', 'ANONYMOUS'))
);

-- The public list and the admin queue both filter on status.
CREATE INDEX IF NOT EXISTS idx_testimonials_status_reviewed
    ON testimonials (status, reviewed_at);
