


    
  
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
