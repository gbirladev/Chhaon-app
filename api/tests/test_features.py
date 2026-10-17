def test_features_are_off_by_default_and_need_no_login(client):
    response = client.get("/features")
    assert response.status_code == 200 and response.json() == {"escalation": False, "love": False}


def test_features_follow_the_server_switch(client, settings):
    settings.escalation_enabled = True
    assert client.get("/features").json() == {"escalation": True, "love": False}
