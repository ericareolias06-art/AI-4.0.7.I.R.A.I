
const API_BASE = "https://ai-4-0-7-i-r-a-i-1.onrender.com";

const message = document.getElementById("message");
const monthlyButton = document.getElementById("monthlyButton");
const yearlyButton = document.getElementById("yearlyButton");
const promoForm = document.getElementById("promoForm");
const promoCode = document.getElementById("promoCode");
const continueButton = document.getElementById("continueButton");

function showMessage(text, isError = false) {
    message.textContent = text;
    message.style.color = isError ? "crimson" : "inherit";
}

async function checkAccount() {
    try {
        const response = await fetch(`${API_BASE}/api/me`, {
            method: "GET",
            credentials: "include"
        });

        if (response.status === 401) {
            showMessage("Please log in first, then return to this page.", true);
            return;
        }

        if (!response.ok) {
            showMessage("Couldn't check your account. Please refresh.", true);
            return;
        }

        const account = await response.json();

        if (account.premium) {
            showMessage("Your account already has Premium access.");
            continueButton.hidden = false;
        }
    } catch (error) {
        showMessage("Couldn't connect to the server. Please try again.", true);
    }
}

async function startCheckout(plan, button) {
    const originalText = button.textContent;
    button.disabled = true;
    monthlyButton.disabled = true;
    yearlyButton.disabled = true;
    showMessage("Connecting to secure Stripe checkout...");

    try {
        const response = await fetch(`${API_BASE}/api/create-checkout-session`, {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ plan })
        });

        const data = await response.json().catch(() => ({}));

        if (!response.ok) {
            if (response.status === 401) {
                throw new Error("Please log in before subscribing.");
            }
            throw new Error(data.message || "Unable to start checkout.");
        }

        if (!data.url) {
            throw new Error("Stripe didn't return a checkout link. Please try again.");
        }

        window.location.href = data.url;
    } catch (error) {
        showMessage(error.message || "Something went wrong. Please try again.", true);
        monthlyButton.disabled = false;
        yearlyButton.disabled = false;
        button.textContent = originalText;
    }
}

monthlyButton.addEventListener("click", () => {
    startCheckout("monthly", monthlyButton);
});

yearlyButton.addEventListener("click", () => {
    startCheckout("yearly", yearlyButton);
});

promoForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    const code = promoCode.value.trim();

    if (!code) {
        showMessage("Please enter your promo code.", true);
        return;
    }

    const submitButton = promoForm.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    showMessage("Checking your promo code...");

    try {
        const response = await fetch(`${API_BASE}/api/redeem-promo`, {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ code })
        });

        const data = await response.json().catch(() => ({}));

        if (!response.ok) {
            throw new Error(data.message || "Couldn't redeem that code.");
        }

        showMessage(data.message || "Premium activated!");
        continueButton.hidden = false;
        promoCode.value = "";
    } catch (error) {
        showMessage(error.message || "Couldn't redeem the promo code.", true);
    } finally {
        submitButton.disabled = false;
    }
});

continueButton.addEventListener("click", () => {
    window.location.href = "home.html";
});

const checkoutResult = new URLSearchParams(window.location.search).get("checkout");

if (checkoutResult === "success") {
    showMessage("Payment submitted! Checking your Premium status...");
    checkAccount();
} else if (checkoutResult === "cancelled") {
    showMessage("Checkout was cancelled. You can choose a plan whenever you're ready.");
} else {
    checkAccount();
}
