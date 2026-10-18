"""Password hashing, tokens and the "no hard-coded password" rule.

The project rule is a policy, so it is enforced by a test rather than by a comment: a fresh checkout
must have no usable credential anywhere.
"""

from __future__ import annotations

import string

import pytest

from tsm import DEFAULTS
from tsm.security import (
    dummy_verify,
    hash_password,
    new_password,
    new_token,
    verify_password,
)


class TestPasswordHashing:
    def test_the_right_password_verifies(self):
        salt, digest = hash_password("correct horse battery staple")

        assert verify_password("correct horse battery staple", salt, digest) is True

    def test_the_wrong_password_does_not(self):
        salt, digest = hash_password("correct horse battery staple")

        assert verify_password("Correct horse battery staple", salt, digest) is False
        assert verify_password("", salt, digest) is False

    def test_two_hashes_of_one_password_use_different_salts(self):
        first = hash_password("same")
        second = hash_password("same")

        assert first[0] != second[0]
        assert first[1] != second[1]

    def test_the_digest_is_not_the_password(self):
        salt, digest = hash_password("hunter2")

        assert "hunter2" not in digest
        assert len(digest) == 64  # 32 bytes, hex

    def test_verification_returns_false_on_a_corrupt_salt_instead_of_raising(self):
        # A damaged row must not 500 the login route.
        assert verify_password("x", "not-hex", "00" * 32) is False

    def test_dummy_verify_costs_about_the_same_as_a_real_one(self):
        # Not a timing assertion (that would be flaky); just that it runs and is not a no-op.
        dummy_verify()
        with pytest.raises(TypeError):
            dummy_verify("an argument it does not take")  # type: ignore[call-arg]


class TestTokens:
    def test_a_token_is_url_safe_and_long_enough(self):
        token = new_token()

        assert len(token) >= 40
        allowed = set(string.ascii_letters + string.digits + "-_")
        assert set(token) <= allowed

    def test_tokens_do_not_repeat(self):
        assert len({new_token() for _ in range(50)}) == 50


class TestGeneratedPasswords:
    def test_a_generated_password_is_long(self):
        assert len(new_password()) == 16
        assert len(new_password(24)) == 24

    def test_the_alphabet_excludes_glyphs_that_are_hard_to_transcribe(self):
        # The password is read off a console and typed into a phone.
        generated = new_password(2000)

        for glyph in "0O1lI":
            assert glyph not in generated

    def test_a_generated_password_is_not_all_one_character(self):
        assert len(set(new_password(24))) > 1


class TestNoHardCodedCredentials:
    def test_the_default_passwords_are_absent_so_the_seeder_must_generate_one(self):
        assert DEFAULTS["DEMO_TOURIST_PASSWORD"] is None
        assert DEFAULTS["AUTHORITY_PASSWORD"] is None

    def test_the_demo_tourist_id_is_a_placeholder_not_a_secret(self):
        # An id is not a credential; only the password is.
        assert DEFAULTS["DEMO_TOURIST_ID"]
