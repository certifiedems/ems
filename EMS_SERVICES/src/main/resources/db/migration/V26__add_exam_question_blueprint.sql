-- Let an admin decide how a paper is built, instead of the server deciding for them.
--
-- The pass mark was already the admin's to set, per exam. The shape of the
-- paper was not: every attempt drew 30 questions split 6/12/12 across LOW,
-- MEDIUM and HIGH because three constants in ExamWorkflowServiceImpl said so.
-- An L1 paper and an L3 paper were therefore the same paper with different
-- questions in it, and moving either one meant a code change and a deploy.
--
-- These columns carry that decision on the exam, next to the pass mark it is
-- always set alongside. The mix is stored as percentages rather than counts
-- because that is how it is reasoned about ("mostly medium, a third hard") and
-- it stays meaningful when the question total is changed; the server turns the
-- percentages into whole-question counts when it builds the paper.
--
-- Defaults reproduce the constants they replace -- 30 questions at 20/40/40 is
-- exactly the old 6/12/12 -- so existing exams keep behaving as they did until
-- someone edits them.

ALTER TABLE exams
    ADD COLUMN IF NOT EXISTS total_questions INT NOT NULL DEFAULT 30;

ALTER TABLE exams
    ADD COLUMN IF NOT EXISTS low_severity_percentage DECIMAL(5, 2) NOT NULL DEFAULT 20.00;

ALTER TABLE exams
    ADD COLUMN IF NOT EXISTS medium_severity_percentage DECIMAL(5, 2) NOT NULL DEFAULT 40.00;

ALTER TABLE exams
    ADD COLUMN IF NOT EXISTS high_severity_percentage DECIMAL(5, 2) NOT NULL DEFAULT 40.00;

ALTER TABLE exams
    ADD CONSTRAINT chk_exam_total_questions
    CHECK (total_questions > 0);

ALTER TABLE exams
    ADD CONSTRAINT chk_exam_severity_percentages
    CHECK (low_severity_percentage >= 0 AND low_severity_percentage <= 100
       AND medium_severity_percentage >= 0 AND medium_severity_percentage <= 100
       AND high_severity_percentage >= 0 AND high_severity_percentage <= 100);

-- The three shares describe one whole paper, so a mix that does not add up is
-- not a mix -- it is a paper with a hole in it or one that overdraws its pool.
ALTER TABLE exams
    ADD CONSTRAINT chk_exam_severity_mix_total
    CHECK (low_severity_percentage + medium_severity_percentage + high_severity_percentage = 100);
