
import os
import sqlite3
import hmac
import hashlib
from datetime import datetime, timedelta, timezone
from pathlib import Path

import stripe
from flask import Flask, request, jsonify, session
from flask_cors import CORS
from werkzeug.security import generate_password_hash, check_password_hash

BASE = Path(__file__).resolve().parent
WEB = BASE / "main"
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
    resources={r"/api/*": {"origins": [FRONTEND_ORIGIN]}},
    supports_credentials=True,
)

app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SECURE=True,
    SESSION_COOKIE_SAMESITE="None",
    PERMANENT_SESSION_LIFETIME=60 * 60 * 8,
)


def get_db():
    DB.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    return conn


def initialize_database():
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL
            )
        """)

        columns = {
            row["name"]
            for row in conn.execute(
                "PRAGMA table_info(accounts)"
            ).fetchall()
        }

        migrations = {
            "premium_until": "TEXT",
            "stripe_customer_id": "TEXT",
            "stripe_subscription_id": "TEXT",
            "stripe_subscription_status": "TEXT",
            "stripe_period_end": "TEXT",
            "stripe_price_id": "TEXT",
        }

        for column, column_type in migrations.items():
            if column not in columns:
                conn.execute(
                    f"ALTER TABLE accounts ADD COLUMN {column} {column_type}"
                )

        conn.execute("""
            CREATE TABLE IF NOT EXISTS promo_redemptions (
                account_id INTEGER NOT NULL,
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
                    "SELECT id FROM accounts WHERE id = ?",
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
                INSERT INTO accounts (email, password_hash)
                VALUES (?, ?)
                """,
                (email, generate_password_hash(password)),
            )

    except sqlite3.IntegrityError:
        return jsonify(
            message="This email is already registered"
        ), 409

    return jsonify(
        message="Account created. You can now sign in."
    ), 201


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
                   stripe_subscription_status, stripe_period_end
            FROM accounts
            WHERE email = ?
            """,
            (email,),
        ).fetchone()

    if account is None or not check_password_hash(
        account["password_hash"], password
    ):
        return jsonify(message="Invalid email or password"), 401

    session.clear()
    session.permanent = True
    session["account_id"] = account["id"]
    session["email"] = account["email"]

    return jsonify(
        message="Login successful",
        premium=has_active_premium(account),
        premium_until=account["premium_until"],
    ), 200


@app.get("/api/me")
def current_account():
    if "account_id" not in session:
        return jsonify(message="Not logged in"), 401

    with get_db() as conn:
        account = conn.execute(
            """
            SELECT id, email, premium_until,
                   stripe_subscription_status, stripe_period_end
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
        premium=has_active_premium(account),
        premium_until=account["premium_until"],
        subscription_status=account["stripe_subscription_status"],
        subscription_period_end=account["stripe_period_end"],
    ), 200


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
                   stripe_subscription_status
            FROM accounts
            WHERE id = ?
            """,
            (session["account_id"],),
        ).fetchone()

    if account is None:
        session.clear()
        return jsonify(message="Account not found"), 401

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
            account = conn.execute(
                """
                SELECT id, premium_until,
                       stripe_subscription_status, stripe_period_end
                FROM accounts
                WHERE id = ?
                """,
                (session["account_id"],),
            ).fetchone()

            if account is None:
                return jsonify(message="Account not found"), 404

            if has_active_premium(account):
                return jsonify(
                    message="Your account already has Premium access"
                ), 409

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

            expires = now + timedelta(days=365)

            conn.execute(
                """
                UPDATE accounts
                SET premium_until = ?
                WHERE id = ?
                """,
                (expires.isoformat(), session["account_id"]),
            )

    except sqlite3.IntegrityError:
        return jsonify(
            message="You have already redeemed this promo code"
        ), 409

    return jsonify(
        message=(
            "Promo code accepted! Premium access granted "
            "for one year."
        ),
        premium=True,
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

    event_type = event["type"]
    obj = event["data"]["object"]

    try:
        if event_type == "checkout.session.completed":
            subscription_id = obj["subscription"]
            account_id = (
                obj.get("client_reference_id")
                or (obj.get("metadata") or {}).get("account_id")
            )
            customer_id = obj.get("customer")

            if subscription_id:
                subscription = stripe.Subscription.retrieve(
                    subscription_id
                )

                if (
                    not (subscription.get("metadata") or {}).get(
                        "account_id"
                    )
                    and account_id
                ):
                    subscription = dict(subscription)
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
                )
                sync_subscription(subscription)

    except (
        stripe.error.StripeError,
        sqlite3.Error,
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
