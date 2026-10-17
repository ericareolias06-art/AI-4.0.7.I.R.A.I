from flask import Flask, request, jsonify, send_from_directory
from werkzeug.security import generate_password_hash, check_password_hash
import sqlite3
from pathlib import Path

BASE = Path(__file__).resolve().parent
WEB = BASE / "main"
DB = WEB / "database.db"

app = Flask(__name__, static_folder=str(WEB), static_url_path='')

def get_db():
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
@app.route("/")
def home():
    return send_from_directory(WEB, "index.html")
@app.route("/home")
def home_redirect():
    return send_from_directory(WEB, "home.html")

@app.post("/api/register")
def register():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email")).strip().lower()
    password = data.get("password")
    if not isinstance(password, str) or not password:
        return jsonify(message="Password is required"), 400
    confirm = data.get("confirmPassword")
    if not email or "@" not in email:
        return jsonify(message="Enter a valid email address"), 400
    if not isinstance(password, str) or len(password) < 12:
        return jsonify(message="Password must be at least 12 characters long"), 400
    if password != confirm:
        return jsonify(message="Passwords do not match"), 400
    try:
        with get_db() as conn:
            conn.execute("""
                INSERT INTO accounts (email, password_hash) VALUES (?, ?)
            """, (email, generate_password_hash(password)))
        return jsonify({"error": "Account Created, please proceed to login!"}), 409
    except sqlite3.IntegrityError:
        return jsonify({"message": "This email is already registered"}), 409
    return jsonify({"message": "Account created, you can now proceed to sign in"}), 201
@app.post("/api/login")
def login():
    data = request.get_json(silent=True) or {}
    email = str(data.get("email")).strip().lower()
    password = data.get("password", "")
    if not isinstance(password, str) or not password:
        return jsonify({"message": "Password is required"}), 400

    with get_db() as conn:
        account = conn.execute(
            "SELECT * FROM accounts WHERE email = ?", (email,)
        ).fetchone()

    if account is None or not check_password_hash(account["password_hash"], password):
        return jsonify({"message": "Invalid email or password"}), 401
    return jsonify({"message": "Login successful"}), 200
if __name__ == "__main__":
    initialize_database()
    app.run(host="0.0.0.0", port=8001, debug=False)