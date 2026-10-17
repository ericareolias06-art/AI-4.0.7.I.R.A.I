const login = document.getElementById("loginForm");
const register = document.getElementById("registerForm");
const toggle = document.getElementById("toggleForm");
const message = document.getElementById("message");

toggle.addEventListener("click", () => {
    const creating =register.hidden
    register.hidden = !creating;
    login.hidden = creating;
    
    toggle.textContent = creating ? "Already have an account? Log in" : "Don't have an account? Register";
    message.textContent = "";
});
register.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = document.getElementById("newEmail").value;
    const password = document.getElementById("newPassword").value;
    const confirm = 
    document.getElementById("newPasswordConfirm").value;
    if (password !== confirm) {
        message.textContent = "Passwords do not match!";
        return;
    }
    try {
        console.log("registeration request starting");
        const response = await fetch("/api/register", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ 
                email, 
                password,
                confirmPassword: confirm
             }),
        });
        const data = await response.json();
        message.textContent =
            data.message ||
            data.error ||
            (response.ok ? "Account created successfully!" : "An error occurred.");
        if (response.ok) {
            register.reset();
            register.hidden = true;
            login.hidden = false;
            toggle.textContent = "Create an account";
        }
    } catch (error) {
        message.textContent = "Cannot connect to login server.";
    }
});
login.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = document.getElementById("email").value;
    const password = document.getElementById("password").value;
    try {
        console.log("login request starting");
        const response = await fetch("/api/login", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ 
                email: email,
                password: password
            }),
        });
        const data = await response.json();
        if (response.ok) {
            window.location.href = "/home";
        }
        else {
            message.textContent = data.message || data.error || "Login Failed.";
        }
    } catch (error) {
        message.textContent = "Cannot connect to login server.";
    }
});