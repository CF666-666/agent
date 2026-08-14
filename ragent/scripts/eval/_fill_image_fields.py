#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性补全 descriptions.jsonl 的 subcategory / image_subject / license 字段。
素材授权由提供者声明:9 张无 license 的图填 proprietary(自有授权)。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESC = ROOT / "bootstrap/data/images/descriptions.jsonl"

# image_path -> (subcategory, image_subject)
SUBJECTS = {
    "drawings/2787306.jpg": ("site_photo", "熔炼炉浇铸"),
    "drawings/2787308.jpg": ("site_photo", "连铸机"),
    "drawings/7148222_2f11a02f-8c4f-4b55-9e2a-6ea8f158c756copy.jpg": ("site_photo", "精馏塔"),
    "drawings/ant-rozetsky-SLIFI67jv5k-unsplash.jpg": ("site_photo", "桥式起重机"),
    "drawings/christian-harb-76yzygeNLT0-unsplash.jpg": ("site_photo", "石化塔器"),
    "drawings/e75a331f1ba426f2b216e8b150519fad.jpg": ("site_photo", "冷却塔"),
    "drawings/homa-appliances-pWUyHVJgLhg-unsplash.jpg": ("site_photo", "装配生产线"),
    "drawings/MAIN1750404271742C5IC9JDV3U.jpg": ("site_photo", "高炉"),
    "drawings/MAIN1750404271746XKP8DIVH23.jpg": ("site_photo", "轧钢车间"),
    "drawings/MAIN1750404271747XZQSW3LIY9.jpg": ("site_photo", "轧机生产线"),
    "drawings/W020250325397883167507_ORIGIN.jpg": ("site_photo", "桥式起重机吊运"),
    "drawings/W020250325397884321254_ORIGIN.jpg": ("site_photo", "机械臂"),
}


def main() -> None:
    rows = [json.loads(line) for line in DESC.read_text(encoding="utf-8").splitlines() if line.strip()]
    updated = 0
    for row in rows:
        path = row["image_path"]
        if path not in SUBJECTS:
            print(f"WARN: unexpected image_path not in map: {path}")
            continue
        subcategory, subject = SUBJECTS[path]
        row["subcategory"] = subcategory
        row["image_subject"] = subject
        if not row.get("license"):
            row["license"] = "proprietary"
        updated += 1
    DESC.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(f"updated {updated} records")


if __name__ == "__main__":
    main()
