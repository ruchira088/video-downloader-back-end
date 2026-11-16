# k8s-postgres AppArmor profile

The postgres StatefulSet runs under the `k8s-postgres` AppArmor profile, which must
be loaded on the node before the pod can start. If it's missing, the pod fails with
`CreateContainerError` and an error about an AppArmor profile that isn't loaded.

## Why

From kernel `7.0.0-43`, AppArmor reports signal and ptrace peers as stacked labels
(`cri-containerd.apparmor.d//&unconfined`). containerd's default profile only allows
`peer=cri-containerd.apparmor.d`, so Postgres was denied sending SIGINT/SIGURG/SIGKILL
to its own processes and hung in crash recovery. `k8s-postgres` is containerd's default
profile plus `signal (send) peer=unconfined` and `ptrace (read) peer=unconfined`.

## Install or update on the node

```bash
sudo install -m 0644 -o root -g root k8s-postgres /etc/apparmor.d/k8s-postgres
sudo apparmor_parser -r -W /etc/apparmor.d/k8s-postgres
grep k8s-postgres /sys/kernel/security/apparmor/profiles   # k8s-postgres (enforce)
```

`apparmor.service` loads everything in `/etc/apparmor.d/` at boot, so this survives
reboots and kernel upgrades.

## Check

```bash
kubectl exec -n <ns> postgres-0 -- cat /proc/1/attr/current   # k8s-postgres//&unconfined (enforce)
sudo journalctl -k --since -10m | grep 'apparmor="DENIED"' | grep k8s-postgres   # no output
```
