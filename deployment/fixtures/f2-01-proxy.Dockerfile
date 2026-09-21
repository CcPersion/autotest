FROM python:3.12-alpine
COPY f2-01-http-proxy.py /opt/f2-01-http-proxy.py
ENTRYPOINT ["python", "/opt/f2-01-http-proxy.py"]
