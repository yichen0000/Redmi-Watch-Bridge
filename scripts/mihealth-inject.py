#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把桥接模块已落盘的手环睡眠，补写进小米健康（com.mi.health）的本地库。

背景：小米健康 3.59.1 的健康数据是**明文 SQLite**（Room），位于
  /data/data/com.mi.health/databases/<userId>/cn/fitness_data
补写一行 sleep_segment 后，小米健康自己的 UI、评分、阶段图、云上传队列全部正常工作，
无需 hook、无需解密、无需改 APK。

用法（手机已 root 且 adb 可连）：
  python3 mihealth-inject.py                 # 只补齐缺失的睡眠夜，打印计划
  python3 mihealth-inject.py --apply         # 真正写入
  python3 mihealth-inject.py --apply --date 2026-11-22
  python3 mihealth-inject.py --revert        # 用本地备份还原 fitness_data

前置：
  adb、root（KernelSU/Magisk）、模块 outbox 里已有目标日期的 sleep_stage
"""
import argparse, datetime as dt, json, os, sqlite3, subprocess, sys, tempfile, shutil

TZ = dt.timezone(dt.timedelta(hours=8))
ZONE_OFF, ZONE_NAME = 28800, "Asia/Shanghai"
MI = "com.mi.health"
MOD_PKG = "io.github.miam1ku.mibandoplusbridge"
DEFAULT_ADB = os.environ.get("ADB", "adb")


def sh(*args, check=False):
    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"FAILED: {' '.join(args)}\n{r.stdout}{r.stderr}")
    return r.stdout.strip()


class Device:
    def __init__(self, adb):
        self.adb = adb

    def root(self, cmd):
        return sh(self.adb, "shell", "su", "-c", cmd)

    def pull(self, remote, local):
        sh(self.adb, "pull", remote, local, check=True)

    def push(self, local, remote):
        sh(self.adb, "push", local, remote, check=True)

    def stop(self, pkg=MI):
        sh(self.adb, "shell", "am", "force-stop", pkg)
        for _ in range(20):
            if not self.root(f"pidof {pkg}").strip():
                return
            import time; time.sleep(0.4)
        raise SystemExit(f"{pkg} 仍在运行，中止（避免覆盖正在使用的数据库）")

    def start(self, pkg=MI):
        sh(self.adb, "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")


def find_db(dev):
    """定位当前登录账号的 fitness_data（按 userId 子目录）。"""
    out = dev.root(f"ls -1 /data/data/{MI}/databases | grep -E '^[0-9]{{5,}}$' | head -n 1")
    uid = out.strip()
    if not uid:
        raise SystemExit("未找到小米健康账号库目录")
    return f"/data/data/{MI}/databases/{uid}/cn"


def fetch_outbox(dev, workdir):
    remote = "/data/local/tmp/hob_probe.db"
    dev.root(f"cp -a /data/data/{MOD_PKG}/databases/health-outbox.db {remote} && chmod 644 {remote}")
    local = os.path.join(workdir, "outbox.db")
    dev.pull(remote, local)
    return local


def nights(outbox):
    """{醒来的本地日期: dict(bed, wake, stages)}，只取 complete 的睡眠区间。"""
    con = sqlite3.connect(f"file:{outbox}?mode=ro", uri=True)
    rows = con.execute(
        "select start_ms,end_ms from measurements where kind='sleep_interval' order by start_ms").fetchall()
    out = {}
    for a, b in rows:
        bed, wake = a // 1000, b // 1000
        st = [(x // 1000, y // 1000, json.loads(p)["stage"]) for x, y, p in con.execute(
            "select start_ms,end_ms,payload from measurements where kind='sleep_stage' "
            "and start_ms>=? and end_ms<=? order by start_ms", (a, b))]
        if not st:
            continue
        day = dt.datetime.fromtimestamp(wake, TZ).date()
        out[day] = {"bed": bed, "wake": wake, "stages": st}
    con.close()
    return out


def stat(outbox, kind, lo, hi):
    con = sqlite3.connect(f"file:{outbox}?mode=ro", uri=True)
    vals = [json.loads(p)["value"] for (p,) in con.execute(
        "select payload from measurements where kind=? and start_ms>=? and end_ms<=? and value is not null",
        (kind, lo * 1000, hi * 1000))]
    con.close()
    return [float(v) for v in vals if v is not None]


def build(outbox, night, sid):
    bed, wake, stages = night["bed"], night["wake"], night["stages"]
    deep = sum(b - a for a, b, s in stages if s == 4) // 60
    light = sum(b - a for a, b, s in stages if s == 3) // 60
    rem = sum(b - a for a, b, s in stages if s == 2) // 60
    awake = sum(b - a for a, b, s in stages if s == 5) // 60
    duration = deep + light + rem
    bed_ts = bed - 240                      # 上床时间：入睡前 4 分钟，与原生样本同口径
    bed_duration = wake - bed_ts
    hr = stat(outbox, "heart_rate", bed, wake)
    sp = stat(outbox, "spo2", bed, wake)
    avg = lambda v, d=0: int(round(sum(v) / len(v))) if v else d
    value = {
        "avg_breath": 11,                   # 模块 sleep_breath 是原始波形量级，非呼吸率，取历史口径
        "avg_hr": avg(hr), "avg_spo2": avg(sp),
        "bedtime": bed, "sleep_deep_duration": deep,
        "device_bedtime": bed, "device_wake_up_time": wake,
        "sleep_duration": duration, "bed_timestamp": bed_ts,
        "is_uncomplete": False, "out_bed_timestamp": wake,
        "sleep_light_duration": light, "bed_duration": bed_duration,
        "max_hr": int(max(hr)) if hr else 0, "max_spo2": int(max(sp)) if sp else 0,
        "min_hr": int(min(hr)) if hr else 0, "min_spo2": int(min(sp)) if sp else 0,
        "protoTime": wake, "sleep_rem_duration": rem,
        "duration": duration,
        # 注意：duration 单位是分钟、bed_duration 是秒，效率必须换算，否则 UI 显示 2%
        "sleep_efficiency": int(round(duration * 60 * 100.0 / bed_duration)) if bed_duration else 0,
        "items": [{"start_time": a, "end_time": b, "state": s} for a, b, s in stages],
        "sleep_algorithm_version": 1, "timezone": 32, "version": 5,
        "awake_count": sum(1 for a, b, s in stages if s == 5),
        "sleep_awake_duration": awake, "wake_up_time": wake,
    }
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def existing_days(con, sid):
    return {dt.datetime.fromtimestamp(t, TZ).date()
            for (t,) in con.execute("select time from sleep_segment where key='sleep' and sid=?", (sid,))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=DEFAULT_ADB)
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--date", help="只处理这一天（醒来日期，YYYY-MM-DD）")
    ap.add_argument("--revert", action="store_true")
    ap.add_argument("--backup", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "probe", "mihealth-sleep-backup"))
    a = ap.parse_args()

    dev = Device(a.adb)
    os.makedirs(a.backup, exist_ok=True)

    if a.revert:
        src = os.path.join(a.backup, "fitness_data")
        if not os.path.exists(src):
            raise SystemExit(f"备份不存在: {src}")
        dev.stop()
        dev.push(src, "/data/local/tmp/fd_revert")
        d = find_db(dev)
        dev.root(f"cd {d} && rm -f fitness_data-wal fitness_data-shm && cp -f /data/local/tmp/fd_revert fitness_data "
                 f"&& chown u0_a336:u0_a336 fitness_data && chmod 660 fitness_data && restorecon fitness_data")
        dev.start()
        print("已还原。")
        return

    work = tempfile.mkdtemp(prefix="mhinj-")
    outbox = fetch_outbox(dev, work)
    ns = nights(outbox)

    dev.stop()
    d = find_db(dev)
    dev.root(f"rm -rf /data/local/tmp/mhinj && mkdir -p /data/local/tmp/mhinj && "
             f"cp -a {d}/fitness_data /data/local/tmp/mhinj/ && "
             f"cp -a {d}/fitness_data-wal /data/local/tmp/mhinj/ 2>/dev/null; "
             f"cp -a {d}/fitness_data-shm /data/local/tmp/mhinj/ 2>/dev/null; "
             f"chmod -R 755 /data/local/tmp/mhinj")
    dev.pull("/data/local/tmp/mhinj", work)
    db = os.path.join(work, "mhinj", "fitness_data")
    shutil.copy2(db, os.path.join(a.backup, "fitness_data"))

    con = sqlite3.connect(db)
    con.execute("pragma wal_checkpoint(TRUNCATE)")
    sid = con.execute("select sid from sleep_segment where key='sleep' "
                      "group by sid order by max(time) desc limit 1").fetchone()
    sid = sid[0] if sid else None
    if not sid:
        raise SystemExit("库里没有手环睡眠记录，无法推断设备 sid")
    have = existing_days(con, sid)

    todo = []
    for day, night in sorted(ns.items()):
        if a.date and day.isoformat() != a.date:
            continue
        if day in have:
            continue
        todo.append((day, night))

    if not todo:
        print(f"没有需要补的睡眠（设备 sid={sid}，现有 {len(have)} 天）")
        con.close()
        return

    print(f"设备 sid={sid}，将补写 {len(todo)} 天：")
    for day, night in todo:
        print(f"  {day}  入睡 {dt.datetime.fromtimestamp(night['bed'],TZ):%m-%d %H:%M} "
              f"→ 起床 {dt.datetime.fromtimestamp(night['wake'],TZ):%m-%d %H:%M}")
    if not a.apply:
        print("\n（预演，加 --apply 执行写入）")
        con.close()
        return

    for day, night in todo:
        v = build(outbox, night, sid)
        w = night["wake"]
        con.execute("""insert or replace into sleep_segment
            (key,sid,time,isComplete,value,endTime,zoneOffsetInSec,zoneName,timeIn0Tz,endTimeIn0Tz,isUpload,isDeleted)
            values (?,?,?,?,?,?,?,?,?,?,?,?)""",
            ("sleep", sid, w, 1, v, w + 60, ZONE_OFF, ZONE_NAME, w - ZONE_OFF, w + 60 - ZONE_OFF, 0, 0))
    con.execute("update _version_table_ set version=version+1 where table_name='sleep_segment'")
    con.commit()
    chk = con.execute("pragma integrity_check").fetchone()[0]
    con.execute("pragma wal_checkpoint(TRUNCATE)")
    con.close()
    if chk != "ok":
        raise SystemExit(f"写入后完整性校验失败: {chk}")

    dev.push(db, "/data/local/tmp/fd_inj")
    dev.root(f"cd {d} && rm -f fitness_data-wal fitness_data-shm && cp -f /data/local/tmp/fd_inj fitness_data "
             f"&& chown u0_a336:u0_a336 fitness_data && chmod 660 fitness_data && restorecon fitness_data")
    dev.start()
    print(f"\n已写入 {len(todo)} 天并重启小米健康。备份: {os.path.join(a.backup,'fitness_data')}")


if __name__ == "__main__":
    main()
