from app.redact import MASK, redact


def test_masks_token_shapes():
    # 토큰 모양은 실행 중에 조립한다 — 소스에 그대로 적으면 저장소의 비밀값 검사에 걸릴 수 있다.
    text = "use gh" + "p_" + "a" * 36 + " and xo" + "xb-1234567890-abcdefghij then AK" + "IA" + "ABCDEFGHIJKLMNOP"
    out, n = redact(text)
    assert n == 3
    assert "ghp_" not in out and "xoxb-" not in out and "AKIA" not in out


def test_masks_named_secret_values_but_keeps_the_name():
    out, n = redact('DB_PASSWORD=Sup3rS3cretValue9\napi_key: "Zx81Kq92Lm33Pp"')
    assert n == 2
    assert out == f'DB_PASSWORD={MASK}\napi_key: "{MASK}"'


def test_masks_private_key_block():
    out, n = redact("before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\n-----END RSA PRIVATE KEY-----\nafter")
    assert n == 1 and "MIIEabc" not in out and out.startswith("before") and out.endswith("after")


def test_leaves_placeholders_paths_and_names_alone():
    text = ("password: <your-password>\ntoken=${GITHUB_TOKEN}\nsecret: changeme123\n"
            "access_token=ENVECTOR_ACCESS_TOKEN\ntoken: /etc/envector/token.jwt\n"
            "The token is rotated daily.\nsecret_name: registry-credentials\n")
    out, n = redact(text)
    assert n == 0 and out == text


def test_plain_text_is_unchanged():
    text = "Rollback the release, then check the pods."
    assert redact(text) == (text, 0)


def test_example_code_and_secret_references_are_not_masked():
    text = ('access_token=os.environ["ACCESS_TOKEN"],\n'
            'token = os.environ.get("APP_TOKEN", "")\n'
            'kwargs["access_token"] = token_value_from_caller\n'
            '"requestedTokenType": "urn:ietf:params:oauth:token-type:access_token"\n'
            'SECRET_NAME="evi-sealed-kek-v2"\n'
            'password_file: /run/secrets/db-password-v1\n'
            'token_ttl=3600seconds\n'
            'API_KEY=test1234567890\n')
    out, n = redact(text)
    assert n == 0 and out == text


def test_real_looking_values_are_still_masked_next_to_code():
    out, n = redact('client = Client(api_key="Zx81Kq92Lm33Pp", token=os.environ["T"])')
    assert n == 1 and out == f'client = Client(api_key="{MASK}", token=os.environ["T"])'
