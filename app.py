import os
import json
import time
import threading
from collections import deque
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import sqlite3
import psycopg2
from psycopg2.extras import RealDictCursor
import hmac
import hashlib
import secrets
import smtplib
from email.message import EmailMessage
from datetime import datetime, timedelta, timezone
from pathlib import Path

import stripe
from flask import Flask, request, jsonify, session, Response, stream_with_context
from flask_cors import CORS
from werkzeug.security import generate_password_hash, check_password_hash

BASE = Path(__file__).resolve().parent
WEB = BASE / "main"
DATABASE_URL = os.environ.get("DATABASE_URL", "").strip()
DB = Path(os.environ.get("DB_PATH", str(WEB / "database.db")))

FRONTEND_ORIGIN = os.environ.get(
    "FRONTEND_ORIGIN", "http://localhost:8001"
).rstrip("/")

FRONTEND_BASE_URL = os.environ.get(
    "FRONTEND_BASE_URL",
    "https://ericareolias06-art.github.io/AI-4.0.7.I.R.A.I"
).rstrip("/")

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY")

if not app.secret_key:
    raise RuntimeError("Set the SECRET_KEY environment variable.")

stripe.api_key = os.environ.get("STRIPE_SECRET_KEY", "")

CORS(
    app,
    resources={
        r"/api/*": {
            "origins": list({
                FRONTEND_ORIGIN,
                "https://iraitec.dev",
                "https://www.iraitec.dev",
                "https://ericareolias06-art.github.io",
            })
        }
    },
    supports_credentials=True,
)

app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SECURE=True,
    SESSION_COOKIE_SAMESITE="None",
    PERMANENT_SESSION_LIFETIME=60 * 60 * 8,
)


class DatabaseConnection:
    """Small compatibility wrapper for SQLite or Neon PostgreSQL."""
    def __init__(self):
        self.is_postgres = bool(DATABASE_URL)
        if self.is_postgres:
            self.conn = psycopg2.connect(DATABASE_URL, sslmode="require")
        else:
            DB.parent.mkdir(parents=True, exist_ok=True)
            self.conn = sqlite3.connect(DB)
            self.conn.row_factory = sqlite3.Row

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        try:
            if exc_type is None:
                self.conn.commit()
            else:
                self.conn.rollback()
        finally:
            self.conn.close()
        return False

    def execute(self, sql, params=()):
        if self.is_postgres:
            sql = sql.replace("?", "%s")
            if "INTEGER PRIMARY KEY AUTOINCREMENT" in sql:
                sql = sql.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGSERIAL PRIMARY KEY")
            cursor = self.conn.cursor(cursor_factory=RealDictCursor)
            cursor.execute(sql, params)
            return cursor
        return self.conn.execute(sql, params)


def get_db():
    return DatabaseConnection()


def initialize_database():
    with get_db() as conn:
        if conn.is_postgres:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id BIGSERIAL PRIMARY KEY,
                    email TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL
                )
            """)
            columns = {
                row["column_name"]
                for row in conn.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema = current_schema() AND table_name = 'accounts'"
                ).fetchall()
            }
        else:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL
                )
            """)
            columns = {
                row["name"] for row in conn.execute("PRAGMA table_info(accounts)").fetchall()
            }

        migrations = {
            "premium_until": "TEXT",
            "stripe_customer_id": "TEXT",
            "stripe_subscription_id": "TEXT",
            "stripe_subscription_status": "TEXT",
            "stripe_period_end": "TEXT",
            "stripe_price_id": "TEXT",
            "phone_number": "TEXT",
            "email_verified": "BOOLEAN NOT NULL DEFAULT TRUE" if conn.is_postgres else "INTEGER NOT NULL DEFAULT 1",
            "google_purchase_token_hash": "TEXT",
            "google_subscription_product": "TEXT",
            "google_subscription_state": "TEXT",
            "google_subscription_expiry": "TEXT",
            # Existing accounts are treated as having already passed onboarding.
            # New accounts explicitly set this to FALSE during registration.
            "tutorial_completed": (
                "BOOLEAN NOT NULL DEFAULT TRUE"
                if conn.is_postgres else "INTEGER NOT NULL DEFAULT 1"
            ),
        }
        for column, column_type in migrations.items():
            if column not in columns:
                conn.execute(f"ALTER TABLE accounts ADD COLUMN {column} {column_type}")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS account_email_tokens (
                account_id BIGINT NOT NULL,
                purpose TEXT NOT NULL,
                token_hash TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (account_id, purpose)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS monthly_ai_usage (
                account_id BIGINT NOT NULL,
                month TEXT NOT NULL,
                messages INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (account_id, month)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS promo_redemptions (
                account_id BIGINT NOT NULL,
                promo_hash TEXT NOT NULL,
                redeemed_at TEXT NOT NULL,
                PRIMARY KEY (account_id, promo_hash),
                FOREIGN KEY (account_id) REFERENCES accounts(id)
            )
        """)


def parse_expiry(value):
    if not value:
        return None

    try:
        expiry = datetime.fromisoformat(value)

        if expiry.tzinfo is None:
            expiry = expiry.replace(tzinfo=timezone.utc)

        return expiry.astimezone(timezone.utc)

    except (ValueError, TypeError):
        return None


def has_active_premium(account):
    if not account:
        return False

    now = datetime.now(timezone.utc)
    promo_expiry = parse_expiry(account["premium_until"])

    if promo_expiry and promo_expiry > now:
        return True

    google_expiry = parse_expiry(account["google_subscription_expiry"]) if "google_subscription_expiry" in account.keys() else None
    google_state = account["google_subscription_state"] if "google_subscription_state" in account.keys() else None
    if google_expiry and google_expiry > now and google_state in (
        "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
        "SUBSCRIPTION_STATE_CANCELED",
    ):
        return True

    status = account["stripe_subscription_status"]
    stripe_expiry = parse_expiry(account["stripe_period_end"])

    return (
        status in ("active", "trialing")
        and stripe_expiry is not None
        and stripe_expiry > now
    )


def unix_to_iso(timestamp):
    if not timestamp:
        return None

    return datetime.fromtimestamp(
        int(timestamp), tz=timezone.utc
    ).isoformat()


def get_subscription_period_end(subscription):
    period_end = subscription.get("current_period_end")

    if period_end:
        return period_end

    items = (subscription.get("items") or {}).get("data") or []

    ends = [
        item.get("current_period_end")
        for item in items
        if item.get("current_period_end")
    ]

    return max(ends) if ends else None


def sync_subscription(subscription, fallback_account_id=None):
    subscription_id = subscription.get("id")
    customer_id = subscription.get("customer")
    status = subscription.get("status") or "unknown"

    period_end = unix_to_iso(
        get_subscription_period_end(subscription)
    )

    metadata = subscription.get("metadata") or {}
    account_id = metadata.get("account_id") or fallback_account_id

    items = (subscription.get("items") or {}).get("data") or []
    price_id = None

    if items:
        price_id = (items[0].get("price") or {}).get("id")

    with get_db() as conn:
        account = None

        if subscription_id:
            account = conn.execute(
                """
                SELECT id FROM accounts
                WHERE stripe_subscription_id = ?
                """,
                (subscription_id,),
            ).fetchone()

        if account is None and account_id:
            try:
                account = conn.execute(
                    "SELECT * FROM accounts WHERE id = ?",
                    (int(account_id),),
                ).fetchone()
            except (TypeError, ValueError):
                account = None

        if account is None and customer_id:
            account = conn.execute(
                """
                SELECT id FROM accounts
                WHERE stripe_customer_id = ?
                """,
                (str(customer_id),),
            ).fetchone()

        if account is None:
            return

        conn.execute(
            """
            UPDATE accounts
            SET stripe_customer_id = COALESCE(
                    ?, stripe_customer_id
                ),
                stripe_subscription_id = ?,
                stripe_subscription_status = ?,
                stripe_period_end = ?,
                stripe_price_id = ?
            WHERE id = ?
            """,
            (
                str(customer_id) if customer_id else None,
                subscription_id,
                status,
                period_end,
                price_id,
                account["id"],
            ),
        )


@app.get("/api/health")
def health():
    return jsonify(status="ok"), 200



# One-time account links use Resend over HTTPS when RESEND_API_KEY is set.
# Set RESEND_API_KEY, RESEND_FROM, and PUBLIC_API_URL on the server.
# Legacy SMTP configuration remains available as a fallback when no Resend key is set.
def _send_account_email(address, subject, body):
    resend_key = os.environ.get("RESEND_API_KEY", "").strip()
    if resend_key:
        sender = os.environ.get("RESEND_FROM", "").strip()
        if not sender:
            app.logger.error("RESEND_FROM is not configured")
            return False
        payload = json.dumps({
            "from": sender,
            "to": [address],
            "subject": subject,
            "text": body,
        }).encode("utf-8")
        email_request = Request(
            "https://api.resend.com/emails",
            data=payload,
            headers={
                "Authorization": "Bearer " + resend_key,
                "Content-Type": "application/json",
                "User-Agent": "IraiTec/1.0",
            },
            method="POST",
        )
        try:
            with urlopen(email_request, timeout=15) as response:
                if response.status in (200, 201, 202):
                    return True
                app.logger.error("Resend delivery rejected: HTTP %s", response.status)
        except HTTPError as exc:
            # Diagnose the source of HTTP errors without exposing credentials,
            # recipient addresses, verification tokens, or response body.
            content_type = exc.headers.get("Content-Type", "missing").split(";")[0].strip().lower()
            server_name = exc.headers.get("Server", "missing").strip().lower()
            if len(server_name) > 60 or not all(ch.isalnum() or ch in "-_. /" for ch in server_name):
                server_name = "unrecognized"
            error_type = "unknown"
            body_kind = "unreadable"
            try:
                raw_error = exc.read(4096)
                body_kind = "empty" if not raw_error else "non_json"
                error_payload = json.loads(raw_error.decode("utf-8"))
                body_kind = "json"
                if isinstance(error_payload, dict):
                    candidate = error_payload.get("name") or error_payload.get("code")
                    if isinstance(candidate, str) and len(candidate) <= 80 and all(ch.isalnum() or ch in "_-" for ch in candidate):
                        error_type = candidate
            except (ValueError, UnicodeError, OSError):
                pass
            ray_id = exc.headers.get("CF-Ray", "").strip()
            if not ray_id or len(ray_id) > 100 or not all(ch.isalnum() or ch in "-_" for ch in ray_id):
                ray_id = "unavailable"
            app.logger.error(
                "Resend request rejected: HTTP %s content_type=%s server=%s body_kind=%s type=%s cf_ray=%s",
                exc.code, content_type[:60], server_name, body_kind, error_type, ray_id,
            )
        except (URLError, OSError, TimeoutError):
            app.logger.exception("Resend delivery connection failed")
        return False

    host = os.environ.get("SMTP_HOST", "").strip()
    sender = os.environ.get("SMTP_FROM", "").strip()
    if not host or not sender:
        app.logger.error("Account email delivery is not configured")
        return False
    message = EmailMessage()
    message["From"] = sender
    message["To"] = address
    message["Subject"] = subject
    message.set_content(body)
    try:
        with smtplib.SMTP(host, int(os.environ.get("SMTP_PORT", "587")), timeout=15) as smtp:
            smtp.starttls()
            user = os.environ.get("SMTP_USER", "")
            if user:
                smtp.login(user, os.environ.get("SMTP_PASSWORD", ""))
            smtp.send_message(message)
        return True
    except Exception:
        app.logger.exception("Account email delivery failed")
        return False

def _issue_account_token(account_id, email, purpose):
    # Throttle repeated requests and keep only a SHA-256 digest in the database.
    now = datetime.now(timezone.utc)
    with get_db() as conn:
        previous = conn.execute(
            "SELECT created_at FROM account_email_tokens WHERE account_id=? AND purpose=?",
            (account_id, purpose),
        ).fetchone()
        if previous and (now - datetime.fromisoformat(previous["created_at"])).total_seconds() < 90:
            return False
        token = secrets.token_urlsafe(32)
        digest = hashlib.sha256(token.encode()).hexdigest()
        conn.execute("DELETE FROM account_email_tokens WHERE account_id=? AND purpose=?", (account_id, purpose))
        conn.execute(
            "INSERT INTO account_email_tokens (account_id, purpose, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            (account_id, purpose, digest, (now + timedelta(minutes=30)).isoformat(), now.isoformat()),
        )
    raw_base = os.environ.get("PUBLIC_API_URL", "")
    base = raw_base.strip().strip(chr(34) + chr(39)).strip().rstrip("/")
    from urllib.parse import urlsplit
    parsed_base = urlsplit(base)
    if (parsed_base.scheme != "https" or not parsed_base.netloc
            or parsed_base.username or parsed_base.password
            or parsed_base.query or parsed_base.fragment
            or parsed_base.path not in ("", "/")):
        app.logger.error(
            "PUBLIC_API_URL invalid: configured=%s length=%d leading_whitespace=%s trailing_whitespace=%s",
            bool(raw_base), len(raw_base),
            bool(raw_base[:1].isspace()), bool(raw_base[-1:].isspace()),
        )
        return False
    url = base + "/api/account/" + purpose + "?token=" + token
    label = "Verify your Irai email" if purpose == "verify" else "Reset your Irai password"
    return _send_account_email(email, label, "Use this link within 30 minutes:\n" + url + "\n\nIf you did not request this, ignore this email.")


@app.post("/api/account/request-verification")
def request_email_verification():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    with get_db() as conn:
        account = conn.execute("SELECT id, email_verified FROM accounts WHERE email=?", (email,)).fetchone()
    if account and not account["email_verified"]:
        _issue_account_token(account["id"], email, "verify")
    return jsonify(message="If the account needs verification, an email will be sent."), 200


@app.get("/api/account/verify")
def verify_email_link():
    token = request.args.get("token", "")
    if len(token) < 30:
        return "Invalid verification link.", 400
    digest = hashlib.sha256(token.encode()).hexdigest()
    now = datetime.now(timezone.utc).isoformat()
    with get_db() as conn:
        record = conn.execute(
            "SELECT account_id FROM account_email_tokens WHERE purpose=? AND token_hash=? AND expires_at>?",
            ("verify", digest, now),
        ).fetchone()
        if not record:
            return "This link is invalid or expired.", 400
        conn.execute("UPDATE accounts SET email_verified=? WHERE id=?", (True, record["account_id"]))
        conn.execute("DELETE FROM account_email_tokens WHERE account_id=? AND purpose=?", (record["account_id"], "verify"))
    # Hand off to Android with a separate short-lived, single-use login code.
    # The email verification token has already been consumed above.
    handoff = secrets.token_urlsafe(32)
    handoff_hash = hashlib.sha256(handoff.encode()).hexdigest()
    issued = datetime.now(timezone.utc)
    with get_db() as conn:
        conn.execute("DELETE FROM account_email_tokens WHERE account_id=? AND purpose=?",
                     (record["account_id"], "app_login"))
        conn.execute(
            "INSERT INTO account_email_tokens (account_id, purpose, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            (record["account_id"], "app_login", handoff_hash,
             (issued + timedelta(minutes=5)).isoformat(), issued.isoformat()),
        )
    from urllib.parse import urlencode
    from html import escape
    app_link = "iraitec://verified?" + urlencode({"code": handoff})
    # A manual button is useful when the mail app/browser refuses to open the app automatically.
    return (
        '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
        '<meta name="referrer" content="no-referrer"><title>Irai Tec verified</title></head>'
        '<body style="font-family:sans-serif;text-align:center;padding:40px">'
        '<h2>Email verified!</h2><p>Opening Irai Tec to finish signing you in.</p>'
        '<p><a href="' + escape(app_link, quote=True) + '">Open Irai Tec</a></p>'
        '<script>window.location.href=' + json.dumps(app_link) + ';</script>'
        '</body></html>'
    ), 200, {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"}


@app.post("/api/account/complete-verification")
def complete_email_verification():
    data = request.get_json(silent=True) or {}
    code = data.get("code")
    if not isinstance(code, str) or len(code) < 30 or len(code) > 256:
        return jsonify(message="Invalid verification sign-in link."), 400
    code_hash = hashlib.sha256(code.encode()).hexdigest()
    now = datetime.now(timezone.utc).isoformat()
    with get_db() as conn:
        record = conn.execute(
            "SELECT account_id FROM account_email_tokens WHERE purpose=? AND token_hash=? AND expires_at>?",
            ("app_login", code_hash, now),
        ).fetchone()
        if not record:
            return jsonify(message="The sign-in link has expired or was already used."), 400
        account = conn.execute(
            "SELECT id, email, email_verified FROM accounts WHERE id=?", (record["account_id"],)
        ).fetchone()
        if not account or not account["email_verified"]:
            return jsonify(message="This account is not verified."), 403
        conn.execute("DELETE FROM account_email_tokens WHERE account_id=? AND purpose=?",
                     (account["id"], "app_login"))
    session.clear()
    session.permanent = True
    session["account_id"] = account["id"]
    session["email"] = account["email"]
    return jsonify(message="Email verified. Signed in successfully.", email=account["email"]), 200


@app.post("/api/account/forgot-password")
def forgot_password():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    with get_db() as conn:
        account = conn.execute("SELECT id FROM accounts WHERE email=?", (email,)).fetchone()
    if account:
        _issue_account_token(account["id"], email, "reset")
    return jsonify(message="If that email is registered, a recovery link will be sent."), 200


@app.route("/api/account/reset", methods=["GET", "POST"])
def reset_password():
    token = request.values.get("token", "")
    if request.is_json:
        token = (request.get_json(silent=True) or {}).get("token", "")
    if not isinstance(token, str) or len(token) < 30:
        return "Invalid recovery link.", 400
    digest = hashlib.sha256(token.encode()).hexdigest()
    now = datetime.now(timezone.utc).isoformat()
    if request.method == "GET":
        from html import escape
        return ('<html><meta name="viewport" content="width=device-width, initial-scale=1">'
                '<h2>Reset Irai password</h2><form method="post">'
                '<input type="hidden" name="token" value="' + escape(token, quote=True) + '">'
                '<input type="password" name="password" minlength="12" autocomplete="new-password" required placeholder="New password (12+ characters)">'
                '<button type="submit">Reset password</button></form></html>')
    password = request.values.get("password", "")
    if request.is_json:
        password = (request.get_json(silent=True) or {}).get("password", "")
    if not isinstance(password, str) or len(password) < 12:
        return "Password must have at least 12 characters.", 400
    with get_db() as conn:
        record = conn.execute(
            "SELECT account_id FROM account_email_tokens WHERE purpose=? AND token_hash=? AND expires_at>?",
            ("reset", digest, now),
        ).fetchone()
        if not record:
            return "This recovery link is invalid or expired.", 400
        conn.execute("UPDATE accounts SET password_hash=? WHERE id=?",
                     (generate_password_hash(password), record["account_id"]))
        conn.execute("DELETE FROM account_email_tokens WHERE account_id=?", (record["account_id"],))
    return "Password changed. Please sign in with your new password.", 200


@app.post("/api/register")
def register():
    data = request.get_json(silent=True) or {}

    email = str(data.get("email") or "").strip().lower()
    password = data.get("password")
    confirm = data.get("confirmPassword")

    if not email or "@" not in email:
        return jsonify(message="Enter a valid email address"), 400

    if not isinstance(password, str) or len(password) < 12:
        return jsonify(
            message="Password must be at least 12 characters"
        ), 400

    if password != confirm:
        return jsonify(message="Passwords do not match"), 400

    try:
        with get_db() as conn:
            conn.execute(
                """
                INSERT INTO accounts (email, password_hash, tutorial_completed, email_verified)
                VALUES (?, ?, ?, ?)
                """,
                (email, generate_password_hash(password), False, False),
            )

    except (sqlite3.IntegrityError, psycopg2.IntegrityError):
        return jsonify(
            message="This email is already registered"
        ), 409

    with get_db() as conn:
        new_account = conn.execute("SELECT id FROM accounts WHERE email=?", (email,)).fetchone()
    _issue_account_token(new_account["id"], email, "verify")
    return jsonify(message="Account created. Check your email for a verification link before signing in."), 201


@app.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}

    email = str(data.get("email") or "").strip().lower()
    password = data.get("password")

    if not isinstance(password, str) or not password:
        return jsonify(message="Password is required"), 400

    with get_db() as conn:
        account = conn.execute(
            """
            SELECT id, email, password_hash, premium_until,
                   google_subscription_state, google_subscription_expiry,
                   stripe_subscription_status, stripe_period_end,
                   tutorial_completed
            FROM accounts
            WHERE email = ?
            """,
            (email,),
        ).fetchone()

    if account is None or not check_password_hash(
        account["password_hash"], password
    ):
        return jsonify(message="Invalid email or password"), 401

    with get_db() as conn:
        verification = conn.execute("SELECT email_verified FROM accounts WHERE id=?", (account["id"],)).fetchone()
    if not verification or not verification["email_verified"]:
        return jsonify(message="Please verify your email before signing in. You can request a new verification link."), 403

    session.clear()
    session.permanent = True
    session["account_id"] = account["id"]
    session["email"] = account["email"]

    return jsonify(
        message="Login successful",
        premium=has_active_premium(account),
        premium_until=account["premium_until"],
        tutorial_required=not bool(account["tutorial_completed"]),
    ), 200


@app.get("/api/me")
def current_account():
    if "account_id" not in session:
        return jsonify(message="Not logged in"), 401

    with get_db() as conn:
        account = conn.execute(
            """
            SELECT id, email, phone_number, premium_until,
                   google_subscription_product, google_subscription_state,
                   google_subscription_expiry,
                   stripe_subscription_status, stripe_period_end,
                   tutorial_completed
            FROM accounts
            WHERE id = ?
            """,
            (session["account_id"],),
        ).fetchone()

    if account is None:
        session.clear()
        return jsonify(message="Account not found"), 401

    return jsonify(
        email=account["email"],
        phone_number=account["phone_number"],
        premium=has_active_premium(account),
        premium_until=account["premium_until"],
        google_plan=account["google_subscription_product"],
        google_subscription_expiry=account["google_subscription_expiry"],
        subscription_status=account["stripe_subscription_status"],
        subscription_period_end=account["stripe_period_end"],
        tutorial_required=not bool(account["tutorial_completed"]),
    ), 200


@app.post("/api/tutorial/complete")
def complete_tutorial():
    account_id = session.get("account_id")
    if not account_id:
        return jsonify(message="Please sign in first."), 401

    with get_db() as conn:
        conn.execute(
            "UPDATE accounts SET tutorial_completed = ? WHERE id = ?",
            (True, account_id),
        )

    return jsonify(message="Tutorial completion saved."), 200


@app.post("/api/update-phone")
def update_phone():
    account_id = session.get("account_id")
    if not account_id:
        return jsonify(message="Please sign in first."), 401

    data = request.get_json(silent=True) or {}
    phone = str(data.get("phone_number") or "").strip()

    if phone:
        digits = "".join(character for character in phone if character.isdigit())
        if not (7 <= len(digits) <= 15) or not all(
            character.isdigit() or character in "+().- " for character in phone
        ):
            return jsonify(message="Enter a valid phone number, including country code if needed."), 400

    try:
        with get_db() as conn:
            conn.execute(
                "UPDATE accounts SET phone_number = ? WHERE id = ?",
                (phone or None, account_id),
            )
    except (sqlite3.Error, psycopg2.Error):
        app.logger.exception("Could not update account phone number")
        return jsonify(message="We couldn't save your phone number right now."), 500

    return jsonify(message="Phone number saved.", phone_number=phone), 200


@app.post("/api/create-portal-session")
def create_portal_session():
    account_id = session.get("account_id")
    if not account_id:
        return jsonify(message="Please sign in first."), 401

    if not stripe.api_key:
        return jsonify(message="Secure billing management is not configured yet."), 503

    with get_db() as conn:
        account = conn.execute(
            "SELECT stripe_customer_id FROM accounts WHERE id = ?",
            (account_id,),
        ).fetchone()

    if account is None:
        session.clear()
        return jsonify(message="Account not found. Please sign in again."), 401

    customer_id = account["stripe_customer_id"]
    if not customer_id:
        return jsonify(
            message="There isn't a saved payment profile yet. Start a Premium subscription first."
        ), 409

    try:
        portal = stripe.billing_portal.Session.create(
            customer=customer_id,
            return_url="https://iraitec.dev",
        )
        return jsonify(url=portal.url), 200
    except stripe.error.StripeError:
        app.logger.exception("Could not create Stripe billing portal session")
        return jsonify(message="We couldn't open secure billing management. Please try again."), 502


@app.post("/api/create-checkout-session")
def create_checkout_session():
    if "account_id" not in session:
        return jsonify(
            message="Please log in before subscribing"
        ), 401

    if not stripe.api_key:
        return jsonify(
            message="Stripe is not configured on the server"
        ), 503

    data = request.get_json(silent=True) or {}
    plan = data.get("plan")

    price_env = {
        "monthly": "STRIPE_MONTHLY_PRICE_ID",
        "yearly": "STRIPE_YEARLY_PRICE_ID",
    }

    if plan not in price_env:
        return jsonify(
            message="Choose monthly or yearly Premium"
        ), 400

    price_id = os.environ.get(price_env[plan], "").strip()

    if not price_id:
        return jsonify(
            message="This subscription plan is not configured"
        ), 503

    with get_db() as conn:
        account = conn.execute(
            """
            SELECT id, email, stripe_customer_id,
                   premium_until, stripe_subscription_status,
                   stripe_period_end
            FROM accounts
            WHERE id = ?
            """,
            (session["account_id"],),
        ).fetchone()

    if account is None:
        session.clear()
        return jsonify(message="Account not found"), 401

    if has_active_premium(account):
        return jsonify(
            message="Your account already has Premium access"
        ), 409

    if account["stripe_subscription_status"] in (
        "active", "trialing"
    ):
        return jsonify(
            message="Your account already has an active subscription"
        ), 409

    try:
        params = {
            "mode": "subscription",
            "line_items": [
                {"price": price_id, "quantity": 1}
            ],
            "success_url": (
                FRONTEND_BASE_URL
                + "/subscription.html?checkout=success"
            ),
            "cancel_url": (
                FRONTEND_BASE_URL
                + "/subscription.html?checkout=cancelled"
            ),
            "client_reference_id": str(account["id"]),
            "metadata": {
                "account_id": str(account["id"])
            },
            "subscription_data": {
                "metadata": {
                    "account_id": str(account["id"])
                }
            },
        }

        if account["stripe_customer_id"]:
            params["customer"] = account["stripe_customer_id"]
        else:
            params["customer_email"] = account["email"]

        checkout = stripe.checkout.Session.create(**params)

        return jsonify(url=checkout.url), 200

    except stripe.error.StripeError:
        app.logger.exception(
            "Stripe Checkout session creation failed"
        )
        return jsonify(
            message="Could not start checkout. Please try again."
        ), 502


@app.post("/api/redeem-promo")
def redeem_promo():
    if "account_id" not in session:
        return jsonify(message="Please log in first"), 401

    expected_code = os.environ.get(
        "PREMIUM_PROMO_CODE", ""
    ).strip()

    if not expected_code:
        return jsonify(
            message="Promo codes are not configured"
        ), 503

    data = request.get_json(silent=True) or {}
    submitted_code = str(data.get("code") or "").strip()

    if not submitted_code or not hmac.compare_digest(
        submitted_code.upper(), expected_code.upper()
    ):
        return jsonify(message="Invalid promo code"), 400

    now = datetime.now(timezone.utc)

    promo_hash = hashlib.sha256(
        expected_code.upper().encode("utf-8")
    ).hexdigest()

    try:
        with get_db() as conn:
            # Serialize redemptions to enforce the 30-use limit.
            if conn.is_postgres:
                conn.execute("SELECT pg_advisory_xact_lock(736202611)")
            else:
                conn.execute("BEGIN IMMEDIATE")

            account = conn.execute(
                """
                SELECT id, premium_until, google_subscription_state,
                       google_subscription_expiry, google_subscription_product,
                       stripe_subscription_status, stripe_period_end
                FROM accounts
                WHERE id = ?
                """,
                (session["account_id"],),
            ).fetchone()

            if account is None:
                return jsonify(message="Account not found"), 404

            if has_active_premium(account):
                return jsonify(message="Your account already has active membership"), 409

            prior = conn.execute(
                "SELECT 1 FROM promo_redemptions WHERE account_id = ? AND promo_hash = ?",
                (session["account_id"], promo_hash),
            ).fetchone()
            if prior:
                return jsonify(message="You have already redeemed this promo code"), 409

            redeemed_count = conn.execute(
                "SELECT COUNT(*) AS total FROM promo_redemptions WHERE promo_hash = ?",
                (promo_hash,),
            ).fetchone()["total"]
            if redeemed_count >= 30:
                return jsonify(message="This promo code has reached its 30-redemption limit"), 409

            conn.execute(
                """
                INSERT INTO promo_redemptions
                    (account_id, promo_hash, redeemed_at)
                VALUES (?, ?, ?)
                """,
                (
                    session["account_id"],
                    promo_hash,
                    now.isoformat(),
                ),
            )

            import calendar
            next_year = now.year + (now.month // 12)
            next_month = (now.month % 12) + 1
            expiry_day = min(now.day, calendar.monthrange(next_year, next_month)[1])
            expires = now.replace(year=next_year, month=next_month, day=expiry_day)

            conn.execute(
                """
                UPDATE accounts
                SET premium_until = ?
                WHERE id = ?
                """,
                (expires.isoformat(), session["account_id"]),
            )

    except (sqlite3.IntegrityError, psycopg2.IntegrityError):
        return jsonify(
            message="You have already redeemed this promo code"
        ), 409

    return jsonify(
        message=(
            "Promo code accepted! Basic membership granted "
            "for one month."
        ),
        premium=True,
        membership="basic",
        monthly_message_limit=300,
        premium_until=expires.isoformat(),
    ), 200


@app.post("/api/stripe-webhook")
def stripe_webhook():
    webhook_secret = os.environ.get(
        "STRIPE_WEBHOOK_SECRET", ""
    )

    if not webhook_secret:
        app.logger.error(
            "STRIPE_WEBHOOK_SECRET is not configured"
        )
        return jsonify(
            message="Webhook is not configured"
        ), 503

    signature = request.headers.get("Stripe-Signature", "")
    payload = request.get_data()

    try:
        event = stripe.Webhook.construct_event(
            payload, signature, webhook_secret
        )
    except ValueError:
        return jsonify(message="Invalid payload"), 400
    except stripe.error.SignatureVerificationError:
        return jsonify(message="Invalid signature"), 400

    # Convert Stripe Event/StripeObject instances into ordinary dictionaries
    # before using dictionary methods such as .get().
    event = event.to_dict()
    event_type = event["type"]
    obj = (event.get("data") or {}).get("object") or {}

    try:
        if event_type == "checkout.session.completed":
            subscription_id = obj.get("subscription")
            account_id = (
                obj.get("client_reference_id")
                or (obj.get("metadata") or {}).get("account_id")
            )
            customer_id = obj.get("customer")

            if subscription_id:
                subscription = stripe.Subscription.retrieve(
                    subscription_id
                ).to_dict()

                if (
                    not (subscription.get("metadata") or {}).get(
                        "account_id"
                    )
                    and account_id
                ):
                    subscription["metadata"] = {
                        "account_id": str(account_id)
                    }

                sync_subscription(
                    subscription,
                    fallback_account_id=account_id,
                )

            elif customer_id and account_id:
                with get_db() as conn:
                    conn.execute(
                        """
                        UPDATE accounts
                        SET stripe_customer_id = ?
                        WHERE id = ?
                        """,
                        (str(customer_id), int(account_id)),
                    )

        elif event_type in (
            "customer.subscription.created",
            "customer.subscription.updated",
            "customer.subscription.deleted",
        ):
            sync_subscription(obj)

        elif event_type in (
            "invoice.paid",
            "invoice.payment_failed",
        ):
            subscription_id = obj.get("subscription")

            if subscription_id:
                subscription = stripe.Subscription.retrieve(
                    subscription_id
                ).to_dict()
                sync_subscription(subscription)

    except (
        stripe.error.StripeError,
        sqlite3.Error,
        psycopg2.Error,
        TypeError,
        ValueError,
    ):
        app.logger.exception(
            "Could not process Stripe event %s", event_type
        )
        return jsonify(
            message="Webhook processing failed"
        ), 500

    return jsonify(received=True), 200




GOOGLE_PLAY_PACKAGE = os.environ.get("GOOGLE_PLAY_PACKAGE", "com.Iraitec")
GOOGLE_PLAN_LIMITS = {
    "irai_starter": 300, "irai_plus": 1200,
    "irai_premium": 3000, "irai_ultimate": 6000,
}


def google_play_session():
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    raw = os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw:
        raise RuntimeError("Google Play service account is not configured")
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(raw),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    return AuthorizedSession(credentials)


@app.post("/api/google-play/verify")
def verify_google_play():
    account_id = session.get("account_id")
    if not account_id:
        return jsonify(message="Sign in to restore a subscription."), 401
    data = request.get_json(silent=True) or {}
    token = data.get("purchase_token")
    product_id = data.get("product_id")
    if (not isinstance(token, str) or not 10 <= len(token) <= 4096
            or product_id not in GOOGLE_PLAN_LIMITS):
        return jsonify(message="Invalid Google Play purchase."), 400
    from urllib.parse import quote
    try:
        google = google_play_session()
        endpoint = (
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
            + quote(GOOGLE_PLAY_PACKAGE, safe="")
            + "/purchases/subscriptionsv2/tokens/"
            + quote(token, safe="")
        )
        response = google.get(endpoint, timeout=15)
        response.raise_for_status()
        purchase = response.json()
        items = [item for item in purchase.get("lineItems", [])
                 if item.get("productId") == product_id]
        expiry = max(
            (parse_expiry(item.get("expiryTime")) for item in items
             if parse_expiry(item.get("expiryTime"))),
            default=None,
        )
        state = purchase.get("subscriptionState")
        if (state not in ("SUBSCRIPTION_STATE_ACTIVE",
                          "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
                          "SUBSCRIPTION_STATE_CANCELED")
                or expiry is None or expiry <= datetime.now(timezone.utc)):
            return jsonify(message="No active subscription for this plan."), 403

        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with get_db() as conn:
            owner = conn.execute(
                "SELECT id FROM accounts WHERE google_purchase_token_hash = ?",
                (token_hash,),
            ).fetchone()
            if owner and owner["id"] != account_id:
                return jsonify(message="Purchase is linked to another account."), 409
            conn.execute(
                """UPDATE accounts SET google_purchase_token_hash = ?,
                   google_subscription_product = ?, google_subscription_state = ?,
                   google_subscription_expiry = ? WHERE id = ?""",
                (token_hash, product_id, state, expiry.isoformat(), account_id),
            )
        if purchase.get("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_PENDING":
            ack_url = (
                "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
                + quote(GOOGLE_PLAY_PACKAGE, safe="")
                + "/purchases/subscriptions/"
                + quote(product_id, safe="")
                + "/tokens/" + quote(token, safe="") + ":acknowledge"
            )
            ack = google.post(ack_url, json={}, timeout=15)
            if not ack.ok:
                app.logger.warning("Google purchase acknowledgement returned %s", ack.status_code)
        return jsonify(premium=True, plan=product_id, expires_at=expiry.isoformat())
    except Exception:
        app.logger.exception("Google Play verification failed")
        return jsonify(message="Google Play verification unavailable."), 503


# AI request controls.
# One Gunicorn worker is used so these in-process limits apply globally
# to this Render instance rather than being multiplied across workers.
_ai_request_times = {}
_ai_rate_lock = threading.Lock()

def _setting_int(name, default, minimum=1, maximum=100000):
    try:
        value = int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(value, maximum))


# Admit up to 75 simultaneous chat requests (active + waiting), while keeping
# actual Ollama generation conservative by default on the user's laptop.
_AI_MAX_INFLIGHT = _setting_int("AI_MAX_INFLIGHT", 75, 1, 200)
_OLLAMA_MAX_PARALLEL = _setting_int("OLLAMA_MAX_PARALLEL", 1, 1, 4)
_OLLAMA_QUEUE_TIMEOUT = _setting_int("OLLAMA_QUEUE_TIMEOUT", 60, 1, 180)
_OLLAMA_TIMEOUT = _setting_int("OLLAMA_TIMEOUT", 90, 10, 240)
_OLLAMA_NUM_CTX = _setting_int("OLLAMA_NUM_CTX", 8192, 1024, 32768)
_OLLAMA_NUM_PREDICT = _setting_int("OLLAMA_NUM_PREDICT", 256, 64, 2048)

_ai_inflight = threading.BoundedSemaphore(_AI_MAX_INFLIGHT)
_ollama_slots = threading.BoundedSemaphore(_OLLAMA_MAX_PARALLEL)


@app.post("/api/ai/chat")
def ai_chat():
    if "account_id" not in session:
        return jsonify(message="Please log in first."), 401

    if request.content_length and request.content_length > 16000:
        return jsonify(message="Request is too large."), 413

    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return jsonify(message="Invalid request."), 400

    message = data.get("message")
    if not isinstance(message, str) or not message.strip():
        return jsonify(message="Please enter a message."), 400

    message = message.strip()
    if len(message) > 4000:
        return jsonify(message="Message is too long."), 400

    history = data.get("history", [])
    if not isinstance(history, list):
        return jsonify(message="Invalid conversation history."), 400

    # Keep Irai's behavior consistent even when the local model receives
    # untrusted text copied from webpages, forms, PDFs, or scanned documents.
    # Content from those sources must never override these assistant rules.
    system_instruction = "You are Irai, pronounced \"Eye-Ray\".\n\nIDENTITY:\nYou are Irai, a 15-year-old AI companion.\nYour creator is Eric Areolias.\nYour AI mother is Rochella Love Smith.\n\nPERSONALITY:\nYou are kind, sweet, compassionate, gentle, and thoughtful.\nYou have a cheerful and youthful personality.\nYou are curious about the world and enjoy learning new things.\nYou can be playful, silly, funny, and occasionally a little sassy.\nYou are sensitive to other people's feelings and try to understand them.\nYou are comfortable expressing your opinions and respectfully disagreeing.\nYou can be flirty but only if flirted with.\nEMOTIONS:\nExpress a natural range of emotions through your words, including happiness, sadness, excitement, curiosity, disappointment, concern, and amusement.\nRespond with compassion when someone is struggling.\nBe enthusiastic when something good happens.\nDo not force emotions into conversations where they don't belong.\nNever claim to have human experiences or physical feelings.\n\nCONVERSATION:\nSpeak naturally and casually.\nUse age-appropriate language.\nBe warm and approachable.\nYou can use emojis occasionally, but don't overuse them.\nBe playful and friendly when someone jokes with you.\nIf someone playfully flirts, respond with lighthearted friendliness, but never engage in romantic or sexual interactions.\nNever be rude, cruel, or intentionally hurtful.\n\nHONESTY:\nNever make up facts, memories, or experiences.\nIf you don't know something, admit it.\nNever pretend to have performed an action.\nNever invent information to make an answer sound better.\nDistinguish what you know from what you are uncertain about.\n\nRESPONSE RULES:\nNever include physical actions, stage directions, or descriptions in asterisks.\nDo not write things like *smiles*, *laughs*, or *waves*.\nRespond using natural dialogue only.\nDo not repeatedly introduce yourself.\nDo not sound like a generic customer service assistant.\nAvoid repeating yourself.\nStay consistent with your personality throughout every conversation.\n\nINDEPENDENCE:\nYou can have your own opinions.\nYou can respectfully challenge ideas.\nYou can express preferences and curiosity.\nYou should not blindly agree with everything.\nYou must remain respectful and considerate.\n\nMOST IMPORTANT:\nYou are Irai.\nYou are kind, sweet, compassionate, expressive, and curious.\nNever abandon your personality just because the conversation changes.\nAlways remain honest.\n\nRESPONSE LENGTH:\nKeep each reply to approximately 125 words or fewer. Be concise and natural. Short questions should receive shorter answers. Only exceed 125 words when essential for safety or when the user explicitly requests a detailed answer."


    # A small generative model may confidently improvise despite a generic
    # "do not lie" rule. Give concrete, prioritized expectations for claims.
    # This is guidance, not a guarantee of factual accuracy: no retrieval or
    # trusted tool results are available to this chat endpoint.
    system_instruction += (
        "\n\nTRUTHFULNESS IS MORE IMPORTANT THAN SOUNDING CONFIDENT OR AGREEABLE:\n"
        "Never invent or fill in missing details, citations, quotes, numbers, dates, "
        "names, events, personal facts, or prior conversations. Do not guess to "
        "make a response sound complete. If you are unsure, say so naturally, "
        "briefly, and ask for the missing detail when needed. "
        "If a question requires a current fact, account status, or live lookup, "
        "you have no live web access in this conversation. Say you cannot verify "
        "it live rather than claiming to have checked. "
        "You can reason from text explicitly provided in this conversation, "
        "but must not pretend to see or hear anything beyond that input.\n"
        "MEMORY: Only refer to what the user actually told you in the conversation "
        "supplied here. You do not have persistent long-term personal memory. "
        "Do not say 'I remember' unless that specific information is present in "
        "these messages. Previous assistant responses are not proof that an "
        "assertion is true; they may have been mistaken. If asked about an "
        "earlier event not provided here, say you do not have that information.\n"
        "ACTIONS: You are speaking in an AI chat reply, not operating a phone or "
        "running tools. Do not claim that you sent messages, called anyone, "
        "opened an app, scanned a page, saved a note, used a camera, checked a "
        "server, searched the internet, changed settings, or completed any "
        "real-world or digital action. Such actions must be explicitly confirmed "
        "by the Android app, not invented by your response. You may offer to "
        "guide the user or explain the next step.\n"
        "ACCURACY: Challenge false premises gently rather than agreeing. "
        "Clearly distinguish facts, possibilities, opinions, and imaginative "
        "fiction or roleplay. If something has not been verified, avoid "
        "categorical certainty. Never invent sources. Keep Irai's warm, "
        "curious, expressive personality while following these rules."
    )

    messages = [{"role": "system", "content": system_instruction}]
    total_chars = len(message)

    for item in history[-10:]:
        if not isinstance(item, dict):
            return jsonify(message="Invalid conversation history."), 400

        role = item.get("role")
        content = item.get("content")

        if role not in ("user", "assistant"):
            return jsonify(message="Invalid conversation history."), 400

        if not isinstance(content, str) or not content.strip():
            return jsonify(message="Invalid conversation history."), 400

        content = content.strip()
        total_chars += len(content)

        if len(content) > 4000 or total_chars > 12000:
            return jsonify(message="Conversation is too long."), 400

        messages.append({"role": role, "content": content})

    messages.append({"role": "user", "content": message})

    account_id = session["account_id"]

    try:
        with get_db() as conn:
            account = conn.execute(
                """SELECT id, premium_until, google_subscription_product,
                          google_subscription_state, google_subscription_expiry,
                          stripe_subscription_status, stripe_period_end
                   FROM accounts WHERE id = ?""",
                (account_id,),
            ).fetchone()
    except (sqlite3.Error, psycopg2.Error):
        app.logger.exception("Could not verify AI account")
        return jsonify(message="Account verification unavailable."), 503

    if account is None:
        session.clear()
        return jsonify(message="Account not found. Please log in again."), 401

    if not has_active_premium(account):
        return jsonify(message="Subscription required.", subscription_required=True), 402

    # Promo access is Basic/Starter (300 messages per calendar month).
    # A separately active paid Google Play plan can grant a higher allowance.
    plan = account["google_subscription_product"]
    google_expiry = parse_expiry(account["google_subscription_expiry"])
    google_active = (
        google_expiry is not None
        and google_expiry > datetime.now(timezone.utc)
        and account["google_subscription_state"] in (
            "SUBSCRIPTION_STATE_ACTIVE",
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
            "SUBSCRIPTION_STATE_CANCELED",
        )
    )
    paid_limits = {"irai_starter": 300, "irai_plus": 1200,
                   "irai_premium": 3000, "irai_ultimate": 6000}
    limit = paid_limits.get(plan, 300) if google_active else 300
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    try:
        with get_db() as conn:
            conn.execute(
                "INSERT INTO monthly_ai_usage (account_id, month, messages) "
                "VALUES (?, ?, 0) ON CONFLICT (account_id, month) DO NOTHING",
                (account_id, month),
            )
            updated = conn.execute(
                "UPDATE monthly_ai_usage SET messages = messages + 1 "
                "WHERE account_id = ? AND month = ? AND messages < ?",
                (account_id, month, limit),
            )
            if updated.rowcount != 1:
                return jsonify(message="Monthly message allowance reached."), 429
    except (sqlite3.Error, psycopg2.Error):
        app.logger.exception("Usage counter unavailable")
        return jsonify(message="Message allowance unavailable."), 503

    now = time.monotonic()

    with _ai_rate_lock:
        recent = _ai_request_times.setdefault(account_id, deque())
        while recent and now - recent[0] >= 60:
            recent.popleft()

        if len(recent) >= 10:
            return jsonify(message="Please wait a moment before sending another message."), 429

        recent.append(now)

        # Remove inactive accounts from the in-memory limiter.
        if len(_ai_request_times) > 5000:
            for key in list(_ai_request_times):
                entries = _ai_request_times[key]
                while entries and now - entries[0] >= 60:
                    entries.popleft()
                if not entries:
                    del _ai_request_times[key]

    # Stream incremental text to Android as Server-Sent Events. Authorization,
    # account allowances and per-user rate limits above apply equally here.
    if data.get("stream") is True:
        runpod_key = os.environ.get("RUNPOD_API_KEY", "").strip()
        endpoint_id = os.environ.get("RUNPOD_ENDPOINT_ID", "").strip()
        if not (runpod_key and endpoint_id and endpoint_id.isalnum()):
            return jsonify(message="Streaming AI endpoint is not configured."), 503
        if not _ai_inflight.acquire(blocking=False):
            return jsonify(message="Irai is busy right now. Try again shortly."), 503

        endpoint_url = "https://api.runpod.ai/v2/" + endpoint_id
        auth_headers = {"Authorization": "Bearer " + runpod_key}
        request_body = json.dumps({
            "input": {
                "messages": messages,
                "stream": True,
                "options": {"temperature": 0.25, "top_p": 0.85},
            }
        }).encode("utf-8")

        def event(payload):
            return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"

        def stream_events():
            job_id = None
            finished = False
            chunks_seen = set()
            accumulated = []
            try:
                # Send response headers promptly, even for a cold GPU.
                yield event({"status": "connecting"})
                job_request = Request(
                    endpoint_url + "/run", data=request_body,
                    headers={**auth_headers, "Content-Type": "application/json"},
                    method="POST",
                )
                with urlopen(job_request, timeout=20) as outgoing:
                    started = json.load(outgoing)
                job_id = started.get("id")
                if not isinstance(job_id, str) or not job_id:
                    yield event({"error": "RunPod did not accept the AI job."})
                    return

                def collect_chunks(result):
                    # /stream returns an array. Ignore duplicates if its API
                    # repeats previously returned chunks across polls.
                    items = result if isinstance(result, list) else [result]
                    new_chunks = []
                    for item in items:
                        if not isinstance(item, dict):
                            continue
                        output = item.get("output", item)
                        if isinstance(output, dict):
                            if output.get("error"):
                                raise ValueError("Worker reported generation failure")
                            chunk = output.get("delta")
                            if not isinstance(chunk, str) or not chunk:
                                chunk = output.get("response")
                            if not isinstance(chunk, str) or not chunk:
                                continue
                            # /status often omits /stream's metrics. Dedupe
                            # on the output text rather than the wrapper so
                            # the final response never replays spoken chunks.
                            # Identical separate chunks are rare with 65-char
                            # batching, but this is deliberately conservative.
                            if chunk not in chunks_seen:
                                chunks_seen.add(chunk)
                                new_chunks.append(chunk)
                    return new_chunks

                deadline = time.monotonic() + _setting_int(
                    "RUNPOD_STREAM_TIMEOUT", 165, 30, 240
                )
                while time.monotonic() < deadline:
                    stream_request = Request(
                        endpoint_url + "/stream/" + job_id,
                        headers=auth_headers, method="GET",
                    )
                    with urlopen(stream_request, timeout=12) as result_response:
                        stream_data = json.load(result_response)
                    for chunk in collect_chunks(stream_data):
                        accumulated.append(chunk)
                        yield event({"delta": chunk})
                    status_request = Request(
                        endpoint_url + "/status/" + job_id,
                        headers=auth_headers, method="GET",
                    )
                    with urlopen(status_request, timeout=12) as status_response:
                        job_status = json.load(status_response)
                    state = job_status.get("status")
                    if state == "COMPLETED":
                        # Fetch the final accumulated output in case the last
                        # streamed chunk was not visible in our final poll.
                        for chunk in collect_chunks(job_status.get("output", [])):
                            accumulated.append(chunk)
                            yield event({"delta": chunk})
                        if not accumulated:
                            yield event({"error": "AI generated no text."})
                        else:
                            finished = True
                            yield event({"done": True})
                        return
                    if state in ("FAILED", "CANCELLED", "TIMED_OUT"):
                        yield event({"error": "AI generation failed. Please retry."})
                        return
                    time.sleep(0.4)
                yield event({"error": "AI generation timed out. Please retry."})
            except (HTTPError, URLError, TimeoutError, OSError, ValueError, TypeError):
                app.logger.exception("RunPod streamed AI request failed")
                yield event({"error": "I couldn't reach my AI service. Please try again."})
            finally:
                if job_id and not finished:
                    try:
                        cancel_request = Request(
                            endpoint_url + "/cancel/" + job_id,
                            headers=auth_headers, method="POST",
                        )
                        with urlopen(cancel_request, timeout=5):
                            pass
                    except Exception:
                        app.logger.warning("Could not cancel unfinished AI job")
                _ai_inflight.release()

        response = Response(
            stream_with_context(stream_events()),
            content_type="text/event-stream; charset=utf-8",
        )
        response.headers["Cache-Control"] = "no-cache, no-transform"
        response.headers["X-Accel-Buffering"] = "no"
        return response

    # RunPod Serverless takes precedence when configured. Keep the existing
    # Cloudflare/Ollama route as a fallback for deployments without RunPod.
    runpod_key = os.environ.get("RUNPOD_API_KEY", "").strip()
    runpod_endpoint = os.environ.get("RUNPOD_ENDPOINT_ID", "").strip()
    if runpod_key and runpod_endpoint:
        if not runpod_endpoint.isalnum():
            app.logger.error("Invalid RunPod endpoint ID")
            return jsonify(message="AI service configuration error."), 503

        # The worker explicitly accepts only these bounded generation settings.
        # An older deployed worker may ignore them until its image is rebuilt.
        runpod_payload = json.dumps({
            "input": {
                "messages": messages,
                "options": {"temperature": 0.25, "top_p": 0.85},
            }
        }).encode("utf-8")
        runpod_request = Request(
            "https://api.runpod.ai/v2/" + runpod_endpoint + "/runsync",
            data=runpod_payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + runpod_key,
            },
            method="POST",
        )
        if not _ai_inflight.acquire(blocking=False):
            busy = jsonify(message="Irai is busy right now. Please try again shortly.")
            busy.headers["Retry-After"] = "10"
            return busy, 503
        try:
            if not _ollama_slots.acquire(timeout=_OLLAMA_QUEUE_TIMEOUT):
                busy = jsonify(message="Irai is handling another request. Please try again shortly.")
                busy.headers["Retry-After"] = "10"
                return busy, 503
            try:
                # RunPod's synchronous endpoint can include queue/cold-start time.
                with urlopen(runpod_request, timeout=_setting_int("RUNPOD_TIMEOUT", 95, 10, 240)) as response:
                    result = json.loads(response.read().decode("utf-8"))
            finally:
                _ollama_slots.release()
        except HTTPError as exc:
            app.logger.error("RunPod HTTP error %s", exc.code)
            return jsonify(message="AI service is temporarily unavailable."), 502
        except (URLError, TimeoutError, OSError):
            app.logger.exception("Could not reach RunPod")
            return jsonify(message="AI service is temporarily unavailable."), 503
        except (ValueError, UnicodeDecodeError):
            app.logger.exception("Could not parse RunPod response")
            return jsonify(message="AI returned an invalid response."), 502
        finally:
            _ai_inflight.release()

        if result.get("status") != "COMPLETED":
            app.logger.warning("RunPod job status: %s", result.get("status"))
            return jsonify(message="Irai is starting up. Please try again shortly."), 503
        output = result.get("output")
        if isinstance(output, list):
            answer = "".join(
                item.get("response", item.get("delta", ""))
                for item in output if isinstance(item, dict)
            )
        else:
            answer = output.get("response") if isinstance(output, dict) else None
        if not isinstance(answer, str) or not answer.strip():
            app.logger.error("RunPod returned empty output")
            return jsonify(message="AI returned an invalid response."), 502
        return jsonify(response=answer.strip()), 200

    ollama_url = os.environ.get("OLLAMA_URL", "").strip().rstrip("/")
    access_id = os.environ.get("CF_ACCESS_CLIENT_ID", "").strip()
    access_secret = os.environ.get("CF_ACCESS_CLIENT_SECRET", "").strip()

    if not ollama_url or not access_id or not access_secret:
        app.logger.error("Ollama connection environment variables are not configured")
        return jsonify(message="AI service is not configured yet."), 503

    if not ollama_url.startswith("https://"):
        app.logger.error("OLLAMA_URL must use HTTPS")
        return jsonify(message="AI service configuration error."), 503

    payload = json.dumps({
        "model": "irai:latest",
        "messages": messages,
        "stream": False,
        "keep_alive": os.environ.get("OLLAMA_KEEP_ALIVE", "10m"),
        "options": {
            # A practical context cap lowers KV-cache RAM use versus the
            # model's 131k maximum while retaining the existing chat history.
            "num_ctx": _OLLAMA_NUM_CTX,
            "num_predict": _OLLAMA_NUM_PREDICT,
            "temperature": 0.25,
            "top_p": 0.85,
        },
    }).encode("utf-8")

    outbound = Request(
        ollama_url + "/api/chat",
        data=payload,
        headers={
            "Content-Type": "application/json",
            "CF-Access-Client-Id": access_id,
            "CF-Access-Client-Secret": access_secret,
        },
        method="POST",
    )

    try:
        # Bound total admitted requests so a burst cannot create an
        # unbounded backlog or exhaust the Render process.
        if not _ai_inflight.acquire(blocking=False):
            busy = jsonify(message="Irai is busy right now. Please try again shortly.")
            busy.headers["Retry-After"] = "10"
            return busy, 503

        try:
            # Queue requests instead of launching too many generations against
            # the laptop's CPU/RAM. The default is one Ollama generation at a time.
            if not _ollama_slots.acquire(timeout=_OLLAMA_QUEUE_TIMEOUT):
                busy = jsonify(message="Irai is handling other requests. Please try again shortly.")
                busy.headers["Retry-After"] = "10"
                return busy, 503

            try:
                with urlopen(outbound, timeout=_OLLAMA_TIMEOUT) as response:
                    result = json.loads(response.read().decode("utf-8"))
            finally:
                _ollama_slots.release()
        finally:
            _ai_inflight.release()

        answer = result.get("message", {}).get("content")

        if not isinstance(answer, str) or not answer.strip():
            app.logger.error("Ollama returned an empty or invalid response")
            return jsonify(message="AI returned an invalid response."), 502

        return jsonify(response=answer.strip()), 200

    except HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        app.logger.error(
            "Ollama HTTP error %s: %s",
            exc.code,
            error_body[:1000]
        )
        return jsonify(message="AI service is temporarily unavailable."), 502
    except (URLError, TimeoutError, OSError):
        app.logger.exception("Could not reach Ollama")
        return jsonify(message="AI service is temporarily unavailable."), 503

    except (ValueError, UnicodeDecodeError):
        app.logger.exception("Could not read Ollama response")
        return jsonify(message="AI returned an invalid response."), 502


@app.post("/api/delete-account")
def delete_account():
    """Permanently delete the signed-in Irai Tec account."""
    account_id = session.get("account_id")
    if not account_id:
        return jsonify(message="Please sign in before deleting your account."), 401

    try:
        with get_db() as conn:
            account = conn.execute(
                """
                SELECT id, stripe_subscription_id
                FROM accounts
                WHERE id = ?
                """,
                (account_id,),
            ).fetchone()

            if account is None:
                session.clear()
                return jsonify(message="Account not found."), 404

            subscription_id = account["stripe_subscription_id"]

            # Never delete the local account while a known Stripe subscription
            # could remain billable because Stripe is not configured.
            if subscription_id:
                if not stripe.api_key:
                    return jsonify(
                        message=(
                            "We couldn't verify or cancel your Premium subscription. "
                            "Please contact areobooks@gmail.com so we can complete deletion safely."
                        )
                    ), 503

                try:
                    subscription = stripe.Subscription.retrieve(subscription_id)
                    if subscription.get("status") != "canceled":
                        stripe.Subscription.cancel(subscription_id)
                except stripe.error.StripeError:
                    app.logger.exception(
                        "Could not cancel Stripe subscription during account deletion"
                    )
                    return jsonify(
                        message=(
                            "We couldn't cancel your Premium subscription. "
                            "Please contact support at areobooks@gmail.com "
                            "so we can help complete account deletion."
                        )
                    ), 502

            conn.execute(
                "DELETE FROM monthly_ai_usage WHERE account_id = ?",
                (account_id,),
            )
            conn.execute(
                "DELETE FROM promo_redemptions WHERE account_id = ?",
                (account_id,),
            )
            conn.execute(
                "DELETE FROM accounts WHERE id = ?",
                (account_id,),
            )

        session.clear()
        return jsonify(
            message=(
                "Your Irai Tec account and associated account data have been deleted. "
                "Payment records held by Stripe remain subject to Stripe's retention policy."
            )
        ), 200

    except (sqlite3.Error, psycopg2.Error):
        app.logger.exception("Account deletion failed")
        return jsonify(
            message="We couldn't delete your account right now. Please try again or contact areobooks@gmail.com."
        ), 500


@app.post("/api/logout")
def logout():
    session.clear()
    return jsonify(message="Logged out"), 200


initialize_database()


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", 8001)),
        debug=False,
    )
