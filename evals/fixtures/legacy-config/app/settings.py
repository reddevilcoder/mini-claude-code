import os

DB_URL = os.environ.get("APP_DB_URL", "sqlite:///local.db")
REQUEST_TIMEOUT_SECONDS = int(os.environ.get("APP_TIMEOUT", "30"))
FEATURE_FLAGS = {"new_checkout": False}
