
const API_BASE = "https://ai-4-0-7-i-r-a-i-1.onrender.com";

const promoForm = document.getElementById("promoForm");
const promoCode = document.getElementById("promoCode");
const message = document.getElementById("message");
const continueButton = document.getElementById("continueButton");

async function checkAccount() {
    try {
        const response = await fetch(`${API_BASE}/api/me`, {
            credentials: "include"
        });

        if (!response.ok) {
            message.textContent = "Please sign in first.";
            promoForm.hidden = true;
            return;
        }

        const account = await response.json();

        if (account.premium) {
            message.textContent = "Your Premium subscription is active!";
            promoForm.hidden = true;
            continueButton.hidden = false;
        }
    } catch (error) {
        console.error(error);
        message.textContent =
            "Unable to connect to the server. Please try again.";
    }
}

promoForm.addEventListener("submit", async (event) => {
    event.preventDefault();

    message.textContent = "Checking your promo code...";
    promoForm.querySelector("button").disabled = true;

    try {
        const response = await fetch(`${API_BASE}/api/redeem-promo`, {
            method: "POST",
            credentials: "include",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                code: promoCode.value.trim()
            })
        });

        const data = await response.json();

        if (response.ok) {
            message.textContent =
                "SUCCESS! Your free Premium year is activated!";
            promoForm.hidden = true;
            continueButton.hidden = false;
        } else {
            message.textContent =
                data.message || data.error || "Promo code could not be activated.";
        }
    } catch (error) {
        console.error(error);
        message.textContent =
            "Unable to connect to the server. Please try again.";
    } finally {
        promoForm.querySelector("button").disabled = false;
    }
});

continueButton.addEventListener("click", () => {
    window.location.href = "home.html";
});

checkAccount();
