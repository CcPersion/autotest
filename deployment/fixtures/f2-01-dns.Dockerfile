FROM python:3.12-alpine
COPY f2-01-dns.py /opt/f2-01-dns.py
ENTRYPOINT ["python", "/opt/f2-01-dns.py"]
