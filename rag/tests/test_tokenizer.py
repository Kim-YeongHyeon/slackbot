from app.tokenizer import tokenize


def test_identifier_kept_whole_and_split():
    assert tokenize("Set fs.aio-max-nr first") == ["set", "fs.aio-max-nr", "fs", "aio", "max", "nr", "first"]


def test_flag_and_snake_case():
    assert tokenize("--numa-auto") == ["numa-auto", "numa", "auto"]
    assert tokenize("EVI_ALLOW_PAGEABLE") == ["evi_allow_pageable", "evi", "allow", "pageable"]


def test_english_stopwords_removed_and_lowercased():
    assert tokenize("The index IS on the Server") == ["index", "server"]


def test_korean_particles_stripped():
    assert tokenize("감사 로그를 켜려면") == ["감사", "로그", "켜려면"]
    assert tokenize("서버에서 인덱스가") == ["서버", "인덱스"]


def test_single_syllable_korean_word_is_not_emptied():
    assert tokenize("이 키는") == ["이", "키는"]


def test_nfc_normalisation_makes_decomposed_hangul_match():
    import unicodedata
    assert tokenize(unicodedata.normalize("NFD", "인덱스")) == tokenize("인덱스")


def test_empty_and_none():
    assert tokenize("") == [] and tokenize(None) == []
