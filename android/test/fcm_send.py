# Справжнє push-повідомлення через Firebase (як його надсилає сервер) — на ТЕСТОВУ тему області («…_t»),
# яку слухає лише тестова збірка. Ключ сервісного облікового запису — зі змінної FCM_SA (секрет GitHub).
# python3 fcm_send.py <тема> <назва області> '<JSON зрізу>'
import json, os, sys, time
import google.auth.transport.requests
from google.oauth2 import service_account
import requests

def send(topic, oblast, d):
    assert topic.endswith("_t"), "лише тестові теми"
    sa = json.loads(os.environ["FCM_SA"])
    cred = service_account.Credentials.from_service_account_info(sa, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    cred.refresh(google.auth.transport.requests.Request())
    r = requests.post("https://fcm.googleapis.com/v1/projects/%s/messages:send" % sa["project_id"],
                      headers={"Authorization": "Bearer " + cred.token},
                      json={"message": {"topic": topic, "data": {"v": "1", "o": oblast, "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "d": json.dumps(d, ensure_ascii=False)},
                                        "android": {"priority": "HIGH", "ttl": "300s", "collapse_key": topic}}}, timeout=20)
    r.raise_for_status()
    return r.json()

if __name__ == "__main__":
    print(send(sys.argv[1], sys.argv[2], json.loads(sys.argv[3])))
