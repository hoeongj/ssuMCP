# Cilium FQDN egress — lab results (Track A, 2026-07-11)

Env: kind (Kubernetes v1.35.0) + Cilium 1.19.5, kubeProxyReplacement=true, Hubble. Two pods under default-deny egress. See ADR 0094.

| Probe | Before policy | After policy | Verdict |
|---|---|---|---|
| backend → api.anthropic.com | reachable (HTTP 404) | HTTP 404 | FORWARDED (allowed) |
| backend → api.openai.com | reachable (HTTP 421) | timeout (curl exit 28) | DROPPED |
| backend → one.one.one.one | HTTP 200 | timeout (exit 28) | DROPPED |
| agent → api.anthropic.com | HTTP 404 | timeout (exit 28) | DROPPED (identity separation) |
| agent → one.one.one.one | HTTP 200 | HTTP 200 | FORWARDED (allowed) |
| backend → 169.254.169.254 | — | timeout (exit 28) | DROPPED (metadata/exfil) |

Per-pod identity separation: the same host `api.anthropic.com` is FORWARDED for `backend` and DROPPED for `agent`, purely by pod identity.

Hubble evidence (denied flow):
```
demo/backend:56320 <> 162.159.140.245:443 (world) Policy denied DROPPED (TCP Flags: SYN)
demo/backend <> 169.254.169.254:80 (world) Policy denied DROPPED
```

Default-deny confirmed real via `cilium-dbg endpoint list` (egress Enforcement=Enabled), not assumed.

Note: the DNS visibility rule (matchPattern "*") makes the fqdn cache learn ALL answered domains including denied ones — visibility ≠ allow; enforcement is at the toFQDNs allowlist (proven by the DROPPED verdicts above).
