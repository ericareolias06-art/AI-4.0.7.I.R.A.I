import os
import sqlite3
from pathlib import Path
from flask import Flask, request, jsonify, session
from flask_cors import CORS
from werkzeug.security import generate_password_hash, check_password_hash

import hmac
import hashlib
from datetime import datetime, timedelta, timezone

BASE = Path(__file__).resolve().parent
WEB = BASE / "main"

# Locally this defaults to main/database.db.
# On Render, set DB_PATH=/var/data/database.db
DB = Path(os.environ.get("DB_PATH", str(WEB / "database.db")))

FRONTEND_ORIGIN = os.environ.get(
    "FRONTEND_ORIGIN",
    "http://localhost:8001"
).rstrip("/")

app = Flask(__name__)
@app.get("/api/health")
def health():
    return jsonify({"status": "ok"}), 200
app.secret_key = os.environ.get("SECRET_KEY")

if not app.secret_key:
    raise RuntimeError("Set the SECRET_KEY environment variable.")

CORS(
    app,
    resources={r"/api/*": {"origins": [FRONTEND_ORIGIN]}},
    supports_credentials=True,
)

# Secure cookie settings for HTTPS deployment.
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

        if "premium_until" not in columns:
            conn.execute(
                "ALTER TABLE accounts ADD COLUMN premium_until TEXT"
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

@app.post("/api/register")
def register():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email") or "").strip().lower()
    password = data.get("password")
    confirm = data.get("confirmPassword")

    if not email or "@" not in email:
        return jsonify(message="Enter a valid email address"), 400

    if not isinstance(password, str) or len(password) < 12:
        return jsonify(message="Password must be at least 12 characters"), 400

    if password != confirm:
        return jsonify(message="Passwords do not match"), 400

    try:
        with get_db() as conn:
            conn.execute(
                "INSERT INTO accounts (email, password_hash) VALUES (?, ?)",
                (email, generate_password_hash(password)),
            )
    except sqlite3.IntegrityError:
        return jsonify(message="This email is already registered"), 409

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
            "SELECT id, email, password_hash FROM accounts WHERE email = ?",
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

    return jsonify(message="Login successful"), 200

@app.get("/api/me")
def current_account():
    if "account_id" not in session:
        return jsonify(message="Not logged in"), 401

    return jsonify(
        email=session["email"]
    ), 200

@app.post("/api/logout")
def logout():
    session.clear()
    return jsonify(message="Logged out"), 200

if __name__ == "__main__":
    initialize_database()
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", 8001)),
        debug=False,
    )
else:
    initialize_database()
