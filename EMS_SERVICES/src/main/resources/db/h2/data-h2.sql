-- ems_backend/src/main/resources/db/h2/data-h2.sql
ALTER TABLE user_roles ALTER COLUMN created_by   SET DEFAULT 'SYSTEM';
ALTER TABLE user_roles ALTER COLUMN created_date SET DEFAULT CURRENT_TIMESTAMP;

-- Roles
MERGE INTO roles (name, description, created_by, created_date, updated_by, updated_date)
KEY(name)
VALUES
    ('ADMIN', 'Administrator role with platform management privileges', 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP),
    ('USER',  'Candidate role for certification lifecycle actions',     'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP);

-- Users  (admin-001 password reset 2026-09-03 - see deployment secret; user-001 hash unchanged)
MERGE INTO users (user_id, first_name, last_name, email, mobile_number, password_hash,
                  profile_photo_key, address, years_of_experience, current_skill_level,
                  current_organization, qualification, father_name, enabled, account_non_locked,
                  created_by, created_date, updated_by, updated_date)
KEY(user_id)
VALUES
    ('admin-001', 'System', 'Admin', 'admin@ems.local', '9000000001',
     '$2a$10$a3Cy.Xr8SIDWeS1vbxwb0uBEEFqsyzAPvWavXF/mzMZxPP1e//DWW',
     NULL, 'Head Office', 10, 'L3', 'EMS', 'M.Tech', 'N/A',
     TRUE, TRUE, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP),
    ('user-001',  'Demo',   'User',  'user@ems.local',  '9000000002',
     '$2a$10$G7hNmartKLj/ZELp5nuIDutZ4FHqDtj9nf5fWCgbxP/GovVEIUcWe',
     NULL, 'City Center', 3, 'L1', 'EMS', 'B.Tech', 'N/A',
     TRUE, TRUE, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP);

-- User roles
MERGE INTO user_roles (user_id, role_id, created_by, created_date, updated_by, updated_date)
KEY(user_id, role_id)
SELECT u.id, r.id, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u JOIN roles r ON r.name = 'USER'
WHERE u.user_id IN ('admin-001', 'user-001');

MERGE INTO user_roles (user_id, role_id, created_by, created_date, updated_by, updated_date)
KEY(user_id, role_id)
SELECT u.id, r.id, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u JOIN roles r ON r.name = 'ADMIN'
WHERE u.user_id = 'admin-001';

DELETE FROM refresh_tokens;
DELETE FROM password_reset_tokens;

-- L1 certification for user-001 (ACTIVE)
MERGE INTO certifications (id, user_ref, certification_level, certification_status, issue_date, expiry_date,
                            created_by, created_date, updated_by, updated_date)
KEY(id)
SELECT 1, u.id, 'L1', 'ACTIVE',
       DATEADD('DAY', -30, CURRENT_DATE), DATEADD('DAY', 335, CURRENT_DATE),
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u WHERE u.user_id = 'user-001';

MERGE INTO certification_history (id, certification_ref, event_type, event_description, event_timestamp,
                                   created_by, created_date, updated_by, updated_date)
KEY(id)
SELECT 1, c.id, 'ISSUED', 'Initial L1 certification issued', CURRENT_TIMESTAMP,
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM certifications c WHERE c.id = 1;

-- Clean transactional data for repeatable seed
DELETE FROM certificates;
DELETE FROM exam_attempts;
DELETE FROM video_recordings;
DELETE FROM violations;
DELETE FROM exam_sessions;
DELETE FROM payments;
DELETE FROM certification_applications;
DELETE FROM certification_history WHERE id <> 1;
DELETE FROM questions;
DELETE FROM exams;

-- Exams
MERGE INTO exams (exam_code, exam_name, certification_level, duration_minutes, total_marks,
                  passing_percentage, total_questions, low_severity_percentage,
                  medium_severity_percentage, high_severity_percentage,
                  exam_status, published, scheduled_start_time, scheduled_end_time,
                  created_by, created_date, updated_by, updated_date)
KEY(exam_code)
VALUES
    ('L1-FOUND-001', 'Level 1 Foundation Certification Exam', 'L1', 60, 30.00, 60.00,
     30, 20.00, 40.00, 40.00,
     'SCHEDULED', TRUE, DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('DAY', 30, CURRENT_TIMESTAMP),
     'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP),
    ('L2-ADV-001', 'Level 2 Advanced Certification Exam', 'L2', 90, 40.00, 60.00,
     30, 20.00, 40.00, 40.00,
     'SCHEDULED', TRUE, DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('DAY', 30, CURRENT_TIMESTAMP),
     'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP),
    ('L3-EXPERT-001', 'Level 3 Expert Certification Exam', 'L3', 120, 50.00, 65.00,
     30, 20.00, 40.00, 40.00,
     'SCHEDULED', TRUE, DATEADD('DAY', -1, CURRENT_TIMESTAMP), DATEADD('DAY', 30, CURRENT_TIMESTAMP),
     'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP);

-- Questions
MERGE INTO questions (question_code, certification_level, question_category, question_type, question_text,
                      options_json, correct_options_json, severity, marks, active,
                      created_by, created_date, updated_by, updated_date)
KEY(question_code)
VALUES
-- L1 LOW
('L1-LOW-001','L1','Technical','Single Choice','What does CPU stand for?',
 '["Central Processing Unit","Computer Primary Utility","Central Program Unit","Control Processing User"]',
 '["Central Processing Unit"]','LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-LOW-002','L1','Technical','Single Choice','Which device is primarily used for long-term data storage?',
 '["Hard disk","RAM","CPU cache","Register"]','["Hard disk"]',
 'LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-LOW-003','L1','General','Single Choice','Which protocol is commonly used to load web pages?',
 '["HTTP","FTP","SMTP","SSH"]','["HTTP"]',
 'LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-LOW-004','L1','Compliance','Single Choice','Which one is a strong password practice?',
 '["Use a long unique password","Reuse one password everywhere","Share password by email","Keep password as company name"]',
 '["Use a long unique password"]','LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-LOW-005','L1','Functional','Single Choice','What is the main purpose of an operating system?',
 '["Manage hardware and software resources","Design websites automatically","Replace application software","Only store files"]',
 '["Manage hardware and software resources"]','LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-LOW-006','L1','Technical','Single Choice','Which file extension is commonly associated with a PDF document?',
 '[".pdf",".docx",".xlsx",".pptx"]','[".pdf"]',
 'LOW',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L1 MEDIUM
('L1-MED-001','L1','Technical','Single Choice','Which SQL command is used to retrieve data from a table?',
 '["SELECT","INSERT","UPDATE","DELETE"]','["SELECT"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-002','L1','Technical','Single Choice','Which HTTP method is typically used to create a new resource?',
 '["POST","GET","TRACE","HEAD"]','["POST"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-003','L1','Compliance','Single Choice','What is the best first action after receiving a suspicious email link?',
 '["Report it and avoid clicking","Forward it to everyone","Open it from mobile","Reply with your password"]',
 '["Report it and avoid clicking"]','MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-004','L1','Functional','Single Choice','Which document usually captures software features from a business perspective?',
 '["Requirements document","Keyboard manual","Network cable map","Browser history"]',
 '["Requirements document"]','MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-005','L1','Technical','Single Choice','Which data structure uses First In First Out ordering?',
 '["Queue","Stack","Tree","Graph"]','["Queue"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-006','L1','Technical','Single Choice','Which status code means a request succeeded?',
 '["200","404","500","403"]','["200"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-007','L1','General','Single Choice','Which tool is commonly used to track source code changes?',
 '["Git","Excel","Paint","Zoom"]','["Git"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-008','L1','Compliance','Single Choice','Why is least-privilege access recommended?',
 '["It limits exposure if an account is misused","It improves monitor brightness","It removes backups","It disables logging"]',
 '["It limits exposure if an account is misused"]','MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-009','L1','Technical','Single Choice','Which component resolves a domain name to an IP address?',
 '["DNS","DHCP","NTP","SNMP"]','["DNS"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-010','L1','Functional','Single Choice','In testing, what is a test case?',
 '["A documented set of steps and expected results","A production server","A design color palette","A payroll record"]',
 '["A documented set of steps and expected results"]','MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-011','L1','Technical','Single Choice','Which markup language structures most web pages?',
 '["HTML","SQL","C","Bash"]','["HTML"]',
 'MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-MED-012','L1','Technical','Single Choice','What does API stand for?',
 '["Application Programming Interface","Applied Program Internet","Automated Protocol Instance","Application Process Integration"]',
 '["Application Programming Interface"]','MEDIUM',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L1 HIGH
('L1-HIGH-001','L1','Technical','Single Choice','Which database concept ensures each row can be uniquely identified?',
 '["Primary key","Index hint","Alias","View"]','["Primary key"]',
 'HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-002','L1','Technical','Single Choice','What is the main benefit of version control branches?',
 '["They isolate work before merging","They increase monitor resolution","They replace testing","They encrypt every file automatically"]',
 '["They isolate work before merging"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-003','L1','Compliance','Single Choice','What is personally identifiable information?',
 '["Data that can identify a person","Only public weather data","Only compiled code","Network latency values"]',
 '["Data that can identify a person"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-004','L1','Technical','Single Choice','Which practice best helps prevent SQL injection?',
 '["Use parameterized queries","Concatenate raw user input","Disable backups","Store passwords in plain text"]',
 '["Use parameterized queries"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-005','L1','Functional','Single Choice','What is the purpose of user acceptance testing?',
 '["Validate the solution meets business needs","Measure CPU heat","Replace code review","Create database indexes"]',
 '["Validate the solution meets business needs"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-006','L1','Technical','Single Choice','Which pattern lets a client talk to a server over standard web endpoints?',
 '["REST","RAID","BIOS","OCR"]','["REST"]',
 'HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-007','L1','Compliance','Single Choice','What should happen to access for an employee who leaves the company?',
 '["It should be removed promptly","It should stay active for convenience","It should be shared with a teammate","It should be posted in documentation"]',
 '["It should be removed promptly"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-008','L1','Technical','Single Choice','Which storage model keeps data in rows and columns?',
 '["Relational database","Object code","Message queue","Cache only"]',
 '["Relational database"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-009','L1','Functional','Single Choice','Why are acceptance criteria useful?',
 '["They define when a feature is complete","They replace source control","They remove the need for users","They guarantee zero defects"]',
 '["They define when a feature is complete"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-010','L1','Technical','Single Choice','Which HTTP status code usually means authentication is required or missing?',
 '["401","201","204","302"]','["401"]',
 'HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-011','L1','General','Single Choice','Which environment should be used for live end-user traffic?',
 '["Production","Local scratchpad","Whiteboard","Recycle bin"]',
 '["Production"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L1-HIGH-012','L1','Technical','Single Choice','Which mechanism typically keeps a user logged in securely in this backend?',
 '["JWT access token","Image alt text","CSS variable","CSV export"]',
 '["JWT access token"]','HIGH',1.00,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L2 LOW
('L2-LOW-001','L2','Technical','Single Choice','Which design pattern is most suitable for database connection pooling?',
 '["Object Pool","Singleton","Factory","Observer"]','["Object Pool"]',
 'LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-LOW-002','L2','Technical','Single Choice','What is caching primarily used to improve?',
 '["Application performance","Code readability","Documentation","Team morale"]',
 '["Application performance"]','LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-LOW-003','L2','Functional','Single Choice','Which methodology emphasizes iterative development in short cycles?',
 '["Agile","Waterfall","V-model","Big Bang"]','["Agile"]',
 'LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-LOW-004','L2','Compliance','Single Choice','Which practice ensures data consistency in multi-threaded environments?',
 '["Synchronization","Randomization","Serialization","Tokenization"]',
 '["Synchronization"]','LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-LOW-005','L2','Technical','Single Choice','What does ACID stand for in database transactions?',
 '["Atomicity Consistency Isolation Durability","Application Code Integration Database","Advanced Coding Interface Design","Aggregate Computation Indexing Data"]',
 '["Atomicity Consistency Isolation Durability"]','LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-LOW-006','L2','General','Single Choice','Which tool is commonly used for containerization?',
 '["Docker","Photoshop","Notepad","Blender"]','["Docker"]',
 'LOW',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L2 MEDIUM
('L2-MED-001','L2','Technical','Single Choice','In microservices architecture, what is the primary challenge?',
 '["Service coordination and data consistency","Network is always fast","Easier debugging than monolith","Single point of failure eliminated"]',
 '["Service coordination and data consistency"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-002','L2','Technical','Single Choice','Which pattern is used to handle asynchronous operations in JavaScript?',
 '["Promise","Loop","Switch","If-else"]','["Promise"]',
 'MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-003','L2','Compliance','Single Choice','What is the primary goal of an Information Security Management System?',
 '["Protect confidentiality integrity and availability","Increase network bandwidth","Replace all passwords","Eliminate all access"]',
 '["Protect confidentiality integrity and availability"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-004','L2','Functional','Single Choice','Which technique helps identify performance bottlenecks?',
 '["Profiling","Copy-pasting code","Guessing","Ignoring logs"]','["Profiling"]',
 'MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-005','L2','Technical','Single Choice','What is the purpose of load balancing?',
 '["Distribute traffic across servers","Increase latency intentionally","Reduce server reliability","Simplify monitoring"]',
 '["Distribute traffic across servers"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-006','L2','Technical','Single Choice','Which architectural pattern separates business logic from presentation?',
 '["MVC","CLI","TUI","REPL"]','["MVC"]',
 'MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-007','L2','General','Single Choice','What does CI/CD stand for?',
 '["Continuous Integration Continuous Deployment","Complex Internet Connection Continuous Database","Computer Integrated Circuit Disk","Cloud Infrastructure Configuration"]',
 '["Continuous Integration Continuous Deployment"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-008','L2','Compliance','Single Choice','Which encryption method uses two keys?',
 '["Asymmetric encryption","ROT13","Base64","MD5"]','["Asymmetric encryption"]',
 'MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-009','L2','Technical','Single Choice','What is an index in a database used for?',
 '["Accelerate query performance","Store user passwords","Display advertisements","Replace primary keys"]',
 '["Accelerate query performance"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-010','L2','Functional','Single Choice','Which testing type focuses on system behavior from an end-user perspective?',
 '["Black box testing","White box testing","Unit testing","Code review"]',
 '["Black box testing"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-011','L2','Technical','Single Choice','What does normalization in databases accomplish?',
 '["Reduce data redundancy and improve consistency","Increase storage requirements","Remove all relationships","Eliminate primary keys"]',
 '["Reduce data redundancy and improve consistency"]','MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-MED-012','L2','Technical','Single Choice','Which protocol is used for secure communication over the internet?',
 '["HTTPS","FTP","TELNET","HTTP"]','["HTTPS"]',
 'MEDIUM',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L2 HIGH
('L2-HIGH-001','L2','Technical','Single Choice','What is the CAP theorem in distributed systems?',
 '["Consistency Availability Partition tolerance","Computer Architecture Protocol","Cache Allocation Policy","Connection Authentication Provider"]',
 '["Consistency Availability Partition tolerance"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-002','L2','Technical','Single Choice','Which design pattern allows objects to alter their behavior when state changes?',
 '["State pattern","Strategy pattern","Observer pattern","Decorator pattern"]',
 '["State pattern"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-003','L2','Compliance','Single Choice','What is the purpose of penetration testing?',
 '["Identify security vulnerabilities","Replace production servers","Measure CPU speed","Test network cables"]',
 '["Identify security vulnerabilities"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-004','L2','Technical','Single Choice','What does SOLID principles stand for?',
 '["Single Open Liskov Substitution Interface Dependency","Server Operating Load Interface Database","Secure Object Linked Interface Design","Synchronized Object List Integer Data"]',
 '["Single Open Liskov Substitution Interface Dependency"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-005','L2','Functional','Single Choice','Which technique improves code quality through structured review?',
 '["Code review","Random testing","Skipping documentation","Solo development"]',
 '["Code review"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-006','L2','Technical','Single Choice','What is the purpose of message queues in distributed systems?',
 '["Decouple services and enable asynchronous communication","Replace database connections","Increase latency","Simplify code"]',
 '["Decouple services and enable asynchronous communication"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-007','L2','Compliance','Single Choice','What does GDPR require regarding user data?',
 '["Explicit user consent for data collection","Store all data unencrypted","Share data with all third parties","Disable security features"]',
 '["Explicit user consent for data collection"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-008','L2','Technical','Single Choice','Which HTTP method is idempotent and safe for retrieval?',
 '["GET","POST","PUT","DELETE"]','["GET"]',
 'HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-009','L2','Functional','Single Choice','What is the primary objective of regression testing?',
 '["Ensure new changes do not break existing functionality","Test only new features","Eliminate all old tests","Measure team velocity"]',
 '["Ensure new changes do not break existing functionality"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-010','L2','Technical','Single Choice','Which algorithm is commonly used for authentication token generation?',
 '["HMAC-SHA","ROT13","Caesar cipher","XOR"]','["HMAC-SHA"]',
 'HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-011','L2','General','Single Choice','What is the benefit of Infrastructure as Code?',
 '["Version control and reproducibility of infrastructure","Slower deployments","Manual configuration preferred","Increased costs"]',
 '["Version control and reproducibility of infrastructure"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L2-HIGH-012','L2','Technical','Single Choice','Which pattern handles complex object creation?',
 '["Builder pattern","Array pattern","List pattern","Loop pattern"]',
 '["Builder pattern"]','HIGH',1.33,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L3 LOW
('L3-LOW-001','L3','Technical','Single Choice','What is the primary goal of API versioning?',
 '["Maintain backward compatibility","Remove all old endpoints","Force client migrations","Simplify documentation"]',
 '["Maintain backward compatibility"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-LOW-002','L3','Technical','Single Choice','Which approach ensures high availability?',
 '["Redundancy and failover","Single server deployment","No backups needed","Disable monitoring"]',
 '["Redundancy and failover"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-LOW-003','L3','Functional','Single Choice','What is the advantage of domain-driven design?',
 '["Aligns code with business language","Makes code harder to understand","Reduces team communication","Ignores business requirements"]',
 '["Aligns code with business language"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-LOW-004','L3','Compliance','Single Choice','Which principle restricts access to necessary minimum?',
 '["Principle of least privilege","Full access model","Group access model","Public access model"]',
 '["Principle of least privilege"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-LOW-005','L3','Technical','Single Choice','What does observability in systems include?',
 '["Metrics logs traces","Only error logs","No monitoring needed","Manual checks only"]',
 '["Metrics logs traces"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-LOW-006','L3','General','Single Choice','Which approach ensures reproducible builds?',
 '["Containerization","Random dependencies","Manual builds","Hardcoded paths"]',
 '["Containerization"]','LOW',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L3 MEDIUM
('L3-MED-001','L3','Technical','Single Choice','What is the purpose of a service mesh?',
 '["Manage inter-service communication and resilience","Increase application complexity","Remove monitoring capabilities","Simplify networking"]',
 '["Manage inter-service communication and resilience"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-002','L3','Technical','Single Choice','Which pattern handles temporal coupling in distributed systems?',
 '["Event sourcing","Synchronous calls","Tight coupling","Polling"]',
 '["Event sourcing"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-003','L3','Compliance','Single Choice','What is the primary focus of zero-trust security?',
 '["Never trust by default always verify","Trust all internal traffic","Disable authentication inside network","Allow all connections"]',
 '["Never trust by default always verify"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-004','L3','Functional','Single Choice','Which practice helps identify architectural issues early?',
 '["Architecture review board","Delaying all reviews","Ignoring feedback","Solo decision making"]',
 '["Architecture review board"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-005','L3','Technical','Single Choice','What is the benefit of hexagonal architecture?',
 '["Decouples business logic from frameworks","Increases framework coupling","Simplifies database structure","Removes abstraction layers"]',
 '["Decouples business logic from frameworks"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-006','L3','Technical','Single Choice','Which technique enables gradual traffic shifting?',
 '["Canary deployment","Big bang release","Immediate cutover","No deployment strategy"]',
 '["Canary deployment"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-007','L3','General','Single Choice','What does Site Reliability Engineering focus on?',
 '["Reliability availability and performance","Ignoring operational concerns","Only development tasks","Eliminating monitoring"]',
 '["Reliability availability and performance"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-008','L3','Compliance','Single Choice','Which practice protects against API abuse?',
 '["Rate limiting and throttling","Unlimited access","No authentication","Public credentials"]',
 '["Rate limiting and throttling"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-009','L3','Technical','Single Choice','What does circuit breaker pattern accomplish?',
 '["Prevent cascading failures","Increase service coupling","Remove retry logic","Disable monitoring"]',
 '["Prevent cascading failures"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-010','L3','Functional','Single Choice','Which metric measures system reliability?',
 '["Mean Time Between Failures","Total lines of code","Number of developers","Development speed"]',
 '["Mean Time Between Failures"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-011','L3','Technical','Single Choice','What is the purpose of semantic versioning?',
 '["Communicate changes via version numbers","Use random version numbers","Ignore backward compatibility","Omit version information"]',
 '["Communicate changes via version numbers"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-MED-012','L3','Technical','Single Choice','Which approach manages infrastructure scalability?',
 '["Auto-scaling based on metrics","Manual server management","Fixed capacity only","Ignoring load patterns"]',
 '["Auto-scaling based on metrics"]','MEDIUM',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
-- L3 HIGH
('L3-HIGH-001','L3','Technical','Single Choice','What is the primary challenge in distributed tracing?',
 '["Correlating requests across services","Simplifying single server logs","Ignoring performance metrics","Disabling trace collection"]',
 '["Correlating requests across services"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-002','L3','Technical','Single Choice','Which pattern enables safe schema evolution?',
 '["Backward and forward compatibility","Breaking all existing clients","Ignoring schema changes","Removing versioning"]',
 '["Backward and forward compatibility"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-003','L3','Compliance','Single Choice','What does defense in depth accomplish?',
 '["Multiple security layers reduce risk","Single password is sufficient","Firewalls are unnecessary","Disable encryption"]',
 '["Multiple security layers reduce risk"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-004','L3','Technical','Single Choice','Which concept ensures database transactions maintain consistency?',
 '["Two-phase commit","Eventual inconsistency","Ignoring transaction logs","Single threaded execution"]',
 '["Two-phase commit"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-005','L3','Functional','Single Choice','What is the benefit of chaos engineering?',
 '["Discover resilience gaps before production issues","Avoid testing entirely","Assume systems always work","Ignore failure scenarios"]',
 '["Discover resilience gaps before production issues"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-006','L3','Technical','Single Choice','Which approach optimizes multi-database queries?',
 '["Query federation","Always use single database","Ignore cross-database joins","Duplicate all data everywhere"]',
 '["Query federation"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-007','L3','Compliance','Single Choice','What is the purpose of incident response planning?',
 '["Minimize impact and recovery time","No security incidents","Avoid documentation","Disable alerts"]',
 '["Minimize impact and recovery time"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-008','L3','Technical','Single Choice','Which pattern handles resource contention?',
 '["Bulkhead pattern","Single shared resource","Unlimited concurrency","No isolation"]',
 '["Bulkhead pattern"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-009','L3','Functional','Single Choice','What does observability-driven development emphasize?',
 '["Building systems with monitoring capability","Assuming systems work perfectly","Removing logging statements","Ignoring runtime behavior"]',
 '["Building systems with monitoring capability"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-010','L3','Technical','Single Choice','Which approach ensures configuration security?',
 '["Environment variable secrets management","Hardcoded credentials in code","Credentials in version control","Sharing passwords in emails"]',
 '["Environment variable secrets management"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-011','L3','General','Single Choice','What is the primary goal of platform engineering?',
 '["Enable developer productivity and self-service","Slow down development process","Increase operational complexity","Eliminate automation"]',
 '["Enable developer productivity and self-service"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP),
('L3-HIGH-012','L3','Technical','Single Choice','Which practice ensures data sovereignty compliance?',
 '["Regional data residency policies","Storing data in any location","Ignoring regulations","Public data sharing without consent"]',
 '["Regional data residency policies"]','HIGH',1.67,TRUE,'SYSTEM',CURRENT_TIMESTAMP,'SYSTEM',CURRENT_TIMESTAMP);

-- ===========================================================================
-- Seed: Completed exam journey for user-001 (L1 PASS)
-- Provides non-empty responses for /results/me, /certificates/me
-- and /certifications/history endpoints.
-- ===========================================================================

-- 1. Certification application (PASSED, payment SUCCESS)
INSERT INTO certification_applications (
    user_ref, exam_ref, certification_level,
    application_status, payment_status,
    applied_on, scheduled_exam_time, remarks,
    created_by, created_date, updated_by, updated_date
)
SELECT u.id, e.id, 'L1', 'PASSED', 'SUCCESS',
       DATEADD('DAY', -35, CURRENT_DATE),
       DATEADD('DAY', -30, CURRENT_TIMESTAMP),
       'Seed: L1 Foundation exam completed successfully',
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u JOIN exams e ON e.exam_code = 'L1-FOUND-001'
WHERE u.user_id = 'user-001';

-- 2. Payment (SUCCESS)
INSERT INTO payments (
    transaction_id, user_ref, exam_ref,
    certification_application_ref,
    amount, currency, provider, payment_status, payment_date,
    provider_reference,
    created_by, created_date, updated_by, updated_date
)
SELECT 'TXN-SEED-001',
       u.id, e.id, ca.id,
       500.00, 'INR', 'DEMO', 'SUCCESS',
       DATEADD('DAY', -33, CURRENT_TIMESTAMP),
       'DEMO-REF-001',
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u
JOIN exams e ON e.exam_code = 'L1-FOUND-001'
JOIN certification_applications ca ON ca.user_ref = u.id AND ca.exam_ref = e.id
WHERE u.user_id = 'user-001';

-- 3. Exam session (COMPLETED, no violations)
INSERT INTO exam_sessions (
    session_token, user_ref, exam_ref,
    session_start_time, session_end_time, session_status,
    violation_count, selected_question_ids_json,
    created_by, created_date, updated_by, updated_date
)
SELECT '00000000-0000-0000-0000-000000000001',
       u.id, e.id,
       DATEADD('MINUTE', -65, DATEADD('DAY', -30, CURRENT_TIMESTAMP)),
       DATEADD('DAY', -30, CURRENT_TIMESTAMP),
       'COMPLETED', 0,
       (SELECT '[' || GROUP_CONCAT(CAST(q.id AS VARCHAR) ORDER BY q.id SEPARATOR ',') || ']'
        FROM (SELECT id FROM questions WHERE certification_level = 'L1' LIMIT 30) q),
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u JOIN exams e ON e.exam_code = 'L1-FOUND-001'
WHERE u.user_id = 'user-001';

-- 4. Exam attempt (PASS - 24/30 correct = 80%)
INSERT INTO exam_attempts (
    exam_session_ref, total_questions, attempted_questions,
    correct_answers, wrong_answers, obtained_marks,
    percentage,
    result_status, submitted_at,
    created_by, created_date, updated_by, updated_date
)
SELECT es.id,
       30, 30, 24, 6,
       24.00, 80.00, 'PASS',
       DATEADD('DAY', -30, CURRENT_TIMESTAMP),
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM exam_sessions es
WHERE es.session_token = '00000000-0000-0000-0000-000000000001';

-- 5. Certificate record (references certification id=1 seeded above)
INSERT INTO certificates (
    certificate_number, certification_ref, exam_attempt_ref,
    certificate_url, qr_code_url, verification_url,
    issue_date, expiry_date,
    created_by, created_date, updated_by, updated_date
)
SELECT 'CERT-' || YEAR(CURRENT_DATE) || '-L1-SEED001',
       1,
       ea.id,
       'CERT-' || YEAR(CURRENT_DATE) || '-L1-SEED001.pdf',
       'embedded:qr',
       'http://localhost:8080/api/certificates/verify/CERT-' || YEAR(CURRENT_DATE) || '-L1-SEED001',
       DATEADD('DAY', -30, CURRENT_DATE),
       DATEADD('DAY', 335, CURRENT_DATE),
       'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM exam_attempts ea
JOIN exam_sessions es ON ea.exam_session_ref = es.id
WHERE es.session_token = '00000000-0000-0000-0000-000000000001';
