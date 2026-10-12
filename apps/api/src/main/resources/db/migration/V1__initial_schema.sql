CREATE FUNCTION library_today() RETURNS date LANGUAGE sql STABLE AS $$
 SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date
$$;
CREATE FUNCTION valid_isbn13(value text) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE total integer:=0; i integer;
BEGIN
 IF value IS NULL THEN RETURN true; END IF;
 IF value !~ '^97[89][0-9]{10}$' THEN RETURN false; END IF;
 FOR i IN 1..13 LOOP total:=total+substring(value,i,1)::integer*CASE WHEN i%2=0 THEN 3 ELSE 1 END; END LOOP;
 RETURN total%10=0;
END $$;
CREATE TABLE users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), username varchar(32) NOT NULL CHECK(username~'^[A-Za-z0-9_]{3,32}$'),
 username_key text GENERATED ALWAYS AS(lower(username)) STORED UNIQUE,
 password_hash text NOT NULL, role text NOT NULL CHECK(role IN('admin','reader')), enabled boolean NOT NULL DEFAULT true,
 must_change_password boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE SEQUENCE reader_code_seq;
CREATE TABLE readers (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL UNIQUE REFERENCES users(id),
 code text NOT NULL UNIQUE DEFAULT('R'||lpad(nextval('reader_code_seq')::text,8,'0')),
 name varchar(50) NOT NULL CHECK(length(btrim(name))>0), phone varchar(30), note varchar(500) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE categories (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name varchar(30) NOT NULL CHECK(length(btrim(name))>0 AND name=btrim(name)),
 name_key text GENERATED ALWAYS AS(lower(btrim(name))) STORED UNIQUE,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE books (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), category_id uuid NOT NULL REFERENCES categories(id),
 title varchar(200) NOT NULL CHECK(length(btrim(title))>0), author varchar(200) NOT NULL CHECK(length(btrim(author))>0),
 publisher varchar(200), isbn varchar(13) UNIQUE CHECK(valid_isbn13(isbn)), publication_year smallint CHECK(publication_year BETWEEN 1 AND 9999),
 edition varchar(200), description varchar(2000) NOT NULL DEFAULT '', archived_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE SEQUENCE copy_code_seq;
CREATE TABLE book_copies (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), book_id uuid NOT NULL REFERENCES books(id),
 code text NOT NULL UNIQUE DEFAULT('LIB'||lpad(nextval('copy_code_seq')::text,8,'0')),
 location varchar(100) NOT NULL DEFAULT '', status text NOT NULL DEFAULT 'available' CHECK(status IN('available','borrowed','unavailable','retired')),
 status_reason varchar(500), note varchar(500) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(status NOT IN('unavailable','retired') OR status_reason IS NOT NULL AND length(btrim(status_reason))>0)
);
CREATE TABLE loans (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), reader_id uuid NOT NULL REFERENCES readers(id), copy_id uuid NOT NULL REFERENCES book_copies(id),
 borrowed_at timestamptz NOT NULL DEFAULT now(), original_due_date date NOT NULL, due_date date NOT NULL, returned_at timestamptz,
 borrowed_by uuid NOT NULL REFERENCES users(id), returned_by uuid REFERENCES users(id),
 borrow_days smallint NOT NULL DEFAULT 30 CHECK(borrow_days>0), renewal_days smallint NOT NULL DEFAULT 30 CHECK(renewal_days>0), max_renewals smallint NOT NULL DEFAULT 1 CHECK(max_renewals=1),
 note varchar(500) NOT NULL DEFAULT '', return_note varchar(500) NOT NULL DEFAULT '',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(original_due_date>=(borrowed_at AT TIME ZONE 'Asia/Shanghai')::date), CHECK(due_date>=original_due_date),
 CHECK(returned_at IS NULL OR returned_at>=borrowed_at), CHECK((returned_at IS NULL)=(returned_by IS NULL))
);
CREATE UNIQUE INDEX loans_one_active_copy ON loans(copy_id) WHERE returned_at IS NULL;
CREATE INDEX loans_reader_active ON loans(reader_id,due_date) WHERE returned_at IS NULL;
CREATE INDEX loans_reader_history ON loans(reader_id,borrowed_at DESC,id DESC);
CREATE INDEX loans_history ON loans(borrowed_at DESC,id DESC);
CREATE INDEX loans_overdue ON loans(due_date,id) WHERE returned_at IS NULL;
CREATE INDEX loans_copy_history ON loans(copy_id,borrowed_at DESC);
CREATE INDEX loans_staff ON loans(borrowed_by);
CREATE INDEX loans_return_staff ON loans(returned_by);
CREATE INDEX books_category ON books(category_id,created_at DESC,id DESC);
CREATE INDEX books_catalog ON books(created_at DESC,id DESC) WHERE archived_at IS NULL;
CREATE INDEX copies_book_status ON book_copies(book_id,status);
CREATE TABLE loan_renewals (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), loan_id uuid NOT NULL UNIQUE REFERENCES loans(id), operated_by uuid NOT NULL REFERENCES users(id),
 operated_at timestamptz NOT NULL DEFAULT now(), old_due_date date NOT NULL, new_due_date date NOT NULL CHECK(new_due_date>old_due_date)
);
CREATE INDEX renewals_operator ON loan_renewals(operated_by);
CREATE TABLE sessions (
 token_hash char(64) PRIMARY KEY CHECK(token_hash~'^[0-9a-f]{64}$'), user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user ON sessions(user_id);
CREATE INDEX sessions_expiry ON sessions(expires_at);
CREATE TABLE idempotency_keys (
 actor_id uuid NOT NULL REFERENCES users(id), operation text NOT NULL CHECK(operation IN('borrow','return','renew')),
 key uuid NOT NULL, request_hash char(64) NOT NULL, response jsonb, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(actor_id,operation,key)
);
CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN NEW.updated_at:=now(); RETURN NEW; END$$;
CREATE TRIGGER users_touch BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER readers_touch BEFORE UPDATE ON readers FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER categories_touch BEFORE UPDATE ON categories FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER books_touch BEFORE UPDATE ON books FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER copies_touch BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER loans_touch BEFORE UPDATE ON loans FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE FUNCTION check_copy_loan_consistency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; copy_status text; active_count integer;
BEGIN
 IF TG_TABLE_NAME='book_copies' THEN target:=NEW.id; ELSE target:=NEW.copy_id; END IF;
 SELECT status INTO copy_status FROM book_copies WHERE id=target;
 SELECT count(*) INTO active_count FROM loans WHERE copy_id=target AND returned_at IS NULL;
 IF(copy_status='borrowed' AND active_count<>1) OR(copy_status<>'borrowed' AND active_count<>0) THEN
  RAISE EXCEPTION 'copy and loan inconsistent' USING ERRCODE='23514';
 END IF; RETURN NULL;
END$$;
CREATE CONSTRAINT TRIGGER copy_loan_consistency AFTER INSERT OR UPDATE ON book_copies DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_copy_loan_consistency();
CREATE CONSTRAINT TRIGGER loan_copy_consistency AFTER INSERT OR UPDATE ON loans DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_copy_loan_consistency();
CREATE FUNCTION preserve_loan_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'cannot delete loan history' USING ERRCODE='23514'; END IF;
 IF NEW.reader_id<>OLD.reader_id OR NEW.copy_id<>OLD.copy_id OR NEW.borrowed_at<>OLD.borrowed_at OR NEW.original_due_date<>OLD.original_due_date
 OR NEW.borrowed_by<>OLD.borrowed_by OR NEW.borrow_days<>OLD.borrow_days OR NEW.renewal_days<>OLD.renewal_days OR NEW.max_renewals<>OLD.max_renewals THEN
  RAISE EXCEPTION 'loan origin immutable' USING ERRCODE='23514'; END IF;
 IF OLD.returned_at IS NOT NULL AND (NEW.returned_at IS DISTINCT FROM OLD.returned_at OR NEW.returned_by IS DISTINCT FROM OLD.returned_by OR NEW.due_date<>OLD.due_date) THEN
  RAISE EXCEPTION 'returned loan immutable' USING ERRCODE='23514'; END IF; RETURN NEW;
END$$;
CREATE TRIGGER loans_history_guard BEFORE UPDATE OR DELETE ON loans FOR EACH ROW EXECUTE FUNCTION preserve_loan_history();
CREATE FUNCTION preserve_copy_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.book_id<>OLD.book_id OR NEW.code<>OLD.code THEN RAISE EXCEPTION 'copy identity immutable' USING ERRCODE='23514'; END IF;
 IF OLD.status='retired' AND NEW.status<>'retired' THEN RAISE EXCEPTION 'retired copy immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END$$;
CREATE TRIGGER copies_identity_guard BEFORE UPDATE ON book_copies FOR EACH ROW EXECUTE FUNCTION preserve_copy_identity();
COMMENT ON TABLE books IS '书目及版本；库存由实体副本查询计算';
COMMENT ON TABLE book_copies IS '每一册实物一个唯一馆藏编号';
COMMENT ON TABLE loans IS '历史借阅事实，returned_at NULL 表示未归还';
