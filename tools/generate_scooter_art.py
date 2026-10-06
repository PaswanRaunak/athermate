#!/usr/bin/env python3
"""Original Athr+ scooter drawings.

These are simplified side views drawn for this companion app.
They are not tracings of Ather photographs, brochures, or logos.
"""

from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "android/app/src/main/res/drawable"
ASSETS = ROOT / "android/app/src/main/assets/scooter-art"
PREVIEW = Path("/tmp/scooter-preview")

W, H = 320, 180


def circle(cx, cy, r):
    return (
        f"M{cx - r:.1f},{cy:.1f} "
        f"A{r:.1f},{r:.1f} 0 1 1 {cx + r:.1f},{cy:.1f} "
        f"A{r:.1f},{r:.1f} 0 1 1 {cx - r:.1f},{cy:.1f} Z"
    )


def ellipse(cx, cy, rx, ry):
    return (
        f"M{cx - rx:.1f},{cy:.1f} "
        f"A{rx:.1f},{ry:.1f} 0 1 1 {cx + rx:.1f},{cy:.1f} "
        f"A{rx:.1f},{ry:.1f} 0 1 1 {cx - rx:.1f},{cy:.1f} Z"
    )


def ops_for(family, accent):
    """Side-view scooter: seat over the rear wheel, open floor, bars on the front shield."""
    ops = [("shadow", ellipse(160, 168, 120, 7), 0)]
    rear, front, radius = {
        "450x": ((72, 148), (252, 148), 27),
        "450s": ((78, 150), (246, 150), 26),
        "apex": ((72, 148), (252, 148), 27),
        "rizta": ((70, 150), (258, 150), 25),
        "konarc": ((74, 150), (250, 150), 28),
    }[family]

    if family in {"450x", "apex"}:
        ops += [
            ("fender", "M40,136 C54,114 90,110 108,128 L98,136 C82,124 58,128 46,142 Z", 0),
            ("body", "M46,132 L52,112 C64,92 96,80 132,78 L146,80 L154,112 L188,120 L200,120 L210,92 L222,68 L250,60 L274,72 L262,98 L248,116 L226,126 L150,130 L78,134 Z", 1.6),
            ("panel", "M86,104 L128,98 L122,118 L84,120 Z", 0),
            ("seat", "M58,108 C82,90 118,86 146,94 L136,108 C112,102 82,106 64,118 Z", 0),
            ("tail", "M34,116 L54,106 L50,128 L32,132 Z", 0),
            ("fender", "M220,122 C240,106 274,110 298,132 L276,136 C262,122 240,120 224,132 Z", 0),
            ("lamp", "M248,66 L276,78 L264,92 L240,82 Z", 0),
            ("line", "M246,78 L252,148", 6),
            ("line", "M228,64 L214,34", 5),
            ("line", "M190,40 L242,28", 5),
            ("grip", circle(188, 40, 4.4), 0),
            ("grip", circle(244, 28, 4.4), 0),
        ]
        if family == "apex":
            ops += [
                ("accent", "M18,96 L96,74 L90,90 L22,112 Z", 0),
                ("accent", "M188,124 L236,128 L234,136 L190,132 Z", 0),
            ]
    elif family == "450s":
        ops += [
            ("fender", "M48,140 C62,118 96,114 112,132 L104,138 C90,128 68,132 54,146 Z", 0),
            ("body", "M54,136 C64,114 100,98 142,98 C158,98 166,112 170,124 L196,128 C208,108 224,86 248,82 C272,78 286,96 276,120 L258,132 L168,138 L86,140 Z", 1.6),
            ("panel", "M96,112 L140,108 L134,126 L94,128 Z", 0),
            ("seat", "M66,116 C92,98 128,96 156,106 L146,118 C120,112 92,114 72,126 Z", 0),
            ("fender", "M214,126 C234,112 266,116 290,136 L270,140 C256,126 234,124 218,134 Z", 0),
            ("lamp", circle(268, 100, 10), 0),
            ("line", "M240,96 L246,150", 6),
            ("line", "M232,84 L214,36", 5),
            ("line", "M188,42 L244,32", 5),
            ("grip", circle(186, 42, 4.4), 0),
            ("grip", circle(246, 32, 4.4), 0),
        ]
    elif family == "rizta":
        ops += [
            ("body", "M42,132 L54,108 L132,102 L140,124 L176,130 L188,130 L196,78 L208,46 C228,32 262,34 286,54 L298,84 L276,128 L214,136 L96,140 Z", 1.6),
            ("panel", "M148,124 L196,120 L192,136 L150,138 Z", 0),
            ("panel", "M196,120 L208,52 C226,40 260,42 282,60 L292,88 L274,124 L210,130 Z", 0),
            ("seat", "M52,114 L130,104 L124,120 L56,128 Z", 0),
            ("lamp", "M250,58 L284,72 L272,88 L242,76 Z", 0),
            ("line", "M250,70 L258,150", 6),
            ("line", "M230,48 L208,18", 5.5),
            ("line", "M180,26 L244,14", 5.5),
            ("grip", circle(178, 26, 4.6), 0),
            ("grip", circle(246, 14, 4.6), 0),
        ]
    elif family == "konarc":
        ops += [
            ("panel", "M64,126 L214,118 L220,138 L70,144 Z", 0),
            ("body", "M48,128 L62,104 L124,96 L136,116 L168,120 L180,96 L196,72 L236,64 L268,78 L252,108 L230,120 L140,126 Z", 1.6),
            ("seat", "M60,112 L128,100 L120,116 L64,124 Z", 0),
            ("tail", "M32,112 L58,100 L54,124 L30,130 Z", 0),
            ("lamp", "M214,74 L266,86 L264,96 L212,84 Z", 0),
            ("fender", "M214,124 C234,108 268,112 292,132 L270,136 C256,122 234,120 218,132 Z", 0),
            ("line", "M236,80 L250,150", 6),
            ("line", "M210,70 L196,36", 5),
            ("line", "M170,42 L230,32", 5),
            ("grip", circle(168, 42, 4.4), 0),
            ("grip", circle(232, 32, 4.4), 0),
        ]
    else:
        raise KeyError(family)

    for center in (rear, front):
        ops.append(("tire", circle(center[0], center[1], radius), 0))
        ops.append(("rim", circle(center[0], center[1], radius * 0.62), 0))
        ops.append(("hub", circle(center[0], center[1], radius * 0.22), 0))
    return ops, "#5C6773"


ROLE_FALLBACK = {
    "shadow": "#000000",
    "tire": "#14181E",
    "hub": "#E7EDF3",
    "lamp": "#F7F8FA",
    "grip": "#1C2128",
    "seat": "#161A20",
    "tail": "#12151A",
}


def paint(role, colours, fork):
    body = colours["body"]
    panel = colours["panel"]
    accent = colours["accent"]
    if role == "body":
        return body, "#334155"
    if role == "panel":
        return panel, None
    if role == "fender":
        return body, None
    if role == "seat":
        return colours.get("seat", "#161A20"), None
    if role == "accent":
        return accent, None
    if role == "tail":
        return colours.get("tail", "#12151A"), None
    if role == "rim":
        return accent if colours.get("rim_accent") else "#2C343F", None
    if role == "line":
        return None, fork
    if role == "shadow":
        return "#000000", None
    if role == "grip":
        return accent if colours.get("grip_accent") else "#1C2128", None
    return ROLE_FALLBACK.get(role, body), None


FAMILIES = {
    "450x": {
        "default": "cosmic_black",
        "colours": [
            ("Cosmic Black", "cosmic_black", {"body": "#1A1D22", "panel": "#3A4048", "accent": "#D5DDE6", "seat": "#0E1116"}),
            ("True Red", "true_red", {"body": "#D3122C", "panel": "#8E1020", "accent": "#F4F6F8", "seat": "#1A1014"}),
            ("Space Grey", "space_grey", {"body": "#8E949C", "panel": "#5E656E", "accent": "#F2F4F6", "seat": "#2A2E33"}),
            ("Lunar Grey", "lunar_grey", {"body": "#C9CDD3", "panel": "#8E949C", "accent": "#F7F8FA", "seat": "#3A4048"}),
            ("Still White", "still_white", {"body": "#F5F2EB", "panel": "#D7D1C6", "accent": "#E8EEF2", "seat": "#2C3138"}),
            ("Hyper Sand", "hyper_sand", {"body": "#C6A56E", "panel": "#8C7044", "accent": "#F6F1E6", "seat": "#2A241C"}),
            ("Stealth Blue", "stealth_blue", {"body": "#3C4C5E", "panel": "#243140", "accent": "#D5DEE8", "seat": "#14181E"}),
        ],
    },
    "450s": {
        "default": "cosmic_black",
        "colours": [
            ("Cosmic Black", "cosmic_black", {"body": "#1A1D22", "panel": "#3A4048", "accent": "#D5DDE6", "seat": "#0E1116"}),
            ("Hyper Sand", "hyper_sand", {"body": "#C6A56E", "panel": "#8C7044", "accent": "#F6F1E6", "seat": "#2A241C"}),
            ("Stealth Blue", "stealth_blue", {"body": "#3C4C5E", "panel": "#243140", "accent": "#D5DEE8", "seat": "#14181E"}),
            ("True Red", "true_red", {"body": "#D3122C", "panel": "#8E1020", "accent": "#F4F6F8", "seat": "#1A1014"}),
            ("Still White", "still_white", {"body": "#F5F2EB", "panel": "#D7D1C6", "accent": "#E8EEF2", "seat": "#2C3138"}),
        ],
    },
    "apex": {
        "default": "indium_blue",
        "colours": [
            ("Indium Blue", "indium_blue", {
                "body": "#1E5AA6", "panel": "#143E78", "accent": "#F26B1D",
                "seat": "#101418", "rim_accent": True, "grip_accent": True,
            }),
        ],
    },
    "rizta": {
        "default": "pangong_blue_duo",
        "colours": [
            ("Alphonso Yellow Duo", "alphonso_yellow_duo", {"body": "#F0B429", "panel": "#1F4D3A", "accent": "#F7F3E6", "seat": "#2A2418"}),
            ("Cardamom Green Duo", "cardamom_green_duo", {"body": "#7E8F58", "panel": "#F3E6C4", "accent": "#F7F4EA", "seat": "#243024"}),
            ("Deccan Grey Duo", "deccan_grey_duo", {"body": "#6E737A", "panel": "#E7E2D6", "accent": "#F4F6F8", "seat": "#2A2E33"}),
            ("Deccan Grey Mono", "deccan_grey_mono", {"body": "#6E737A", "panel": "#4E545C", "accent": "#E6E9ED", "seat": "#2A2E33"}),
            ("Pangong Blue Duo", "pangong_blue_duo", {"body": "#1F4E8C", "panel": "#E6EEF4", "accent": "#F7F8FA", "seat": "#16202C"}),
            ("Pangong Blue Super Matte", "pangong_blue_super_matte", {"body": "#1A3F6E", "panel": "#163656", "accent": "#D5DEE8", "seat": "#121820"}),
            ("Siachen White Mono", "siachen_white_mono", {"body": "#F6F4EF", "panel": "#DDD8CE", "accent": "#E8EEF2", "seat": "#2C3138"}),
            ("Terracotta Red Duo", "terracotta_red_duo", {"body": "#C45C3E", "panel": "#F0D3B0", "accent": "#F8F1EA", "seat": "#2A1C16"}),
            ("Terracotta Red Super Matte", "terracotta_red_super_matte", {"body": "#A34B32", "panel": "#7C3824", "accent": "#F0D8CC", "seat": "#241610"}),
        ],
    },
    "konarc": {
        "default": "quartz_white",
        "colours": [
            ("Quartz White", "quartz_white", {"body": "#F4F6F8", "panel": "#2F6FED", "accent": "#2F6FED", "seat": "#1C2430", "grip_accent": True}),
            ("Majestic Grey", "majestic_grey", {"body": "#5C6168", "panel": "#17191C", "accent": "#D5DADF", "seat": "#121416"}),
            ("Celestial Gold", "celestial_gold", {"body": "#C6A15A", "panel": "#1E4E8C", "accent": "#F3E6C4", "seat": "#241C12"}),
            ("Lumen Copper", "lumen_copper", {"body": "#C4784A", "panel": "#1A1614", "accent": "#F0D2C0", "seat": "#241812"}),
            ("Steel Blue", "steel_blue", {"body": "#3E6278", "panel": "#243E4E", "accent": "#D5E4EE", "seat": "#12181C"}),
            ("Claret Red", "claret_red", {"body": "#7C2433", "panel": "#3A1218", "accent": "#F0D0D4", "seat": "#1A1012"}),
        ],
    },
}


def xml_escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;")


def write_vector(path, ops, colours):
    parts = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Original Athr+ illustration. Not an official product image. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{W}dp"',
        f'    android:height="{H}dp"',
        f'    android:viewportWidth="{W}"',
        f'    android:viewportHeight="{H}">',
    ]
    fork = colours.get("_fork", "#5C6773")
    for role, d, sw in ops:
        fill, stroke = paint(role, colours, fork)
        attrs = [f'android:pathData="{d}"']
        if fill:
            attrs.append(f'android:fillColor="{fill}"')
            if role == "shadow":
                attrs.append('android:fillAlpha="0.16"')
        else:
            attrs.append('android:fillColor="#00000000"')
        if stroke and sw:
            attrs.append(f'android:strokeColor="{stroke}"')
            attrs.append(f'android:strokeWidth="{sw}"')
            attrs.append('android:strokeLineCap="round"')
            attrs.append('android:strokeLineJoin="round"')
        elif role == "body":
            attrs.append('android:strokeColor="#334155"')
            attrs.append('android:strokeWidth="1.6"')
            attrs.append('android:strokeLineJoin="round"')
        parts.append("    <path")
        parts.append("        " + "\n        ".join(attrs) + " />")
    parts.append("</vector>")
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")


def write_svg(path, ops, colours, title):
    fork = colours.get("_fork", "#5C6773")
    body = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img">',
        f"  <title>{xml_escape(title)}</title>",
        "  <desc>Original Athr+ illustration. Not an official product image.</desc>",
    ]
    for role, d, sw in ops:
        fill, stroke = paint(role, colours, fork)
        style = []
        if fill:
            style.append(f'fill="{fill}"')
            if role == "shadow":
                style.append('fill-opacity="0.16"')
        else:
            style.append('fill="none"')
        if sw and stroke:
            style.append(f'stroke="{stroke}"')
            style.append(f'stroke-width="{sw}"')
            style.append('stroke-linecap="round"')
            style.append('stroke-linejoin="round"')
        elif role == "body":
            style.append('stroke="#334155"')
            style.append('stroke-width="1.6"')
            style.append('stroke-linejoin="round"')
        body.append(f'  <path {" ".join(style)} d="{d}"/>')
    body.append("</svg>")
    path.write_text("\n".join(body) + "\n", encoding="utf-8")


def main():
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    count = 0
    for family, spec in FAMILIES.items():
        for label, slug, colours in spec["colours"]:
            colours = dict(colours)
            colours["_fork"] = colours["accent"] if family == "apex" else "#5C6773"
            ops, _ = ops_for(family, colours["accent"])
            name = f"scooter_{family}_{slug}"
            write_vector(DRAWABLE / f"{name}.xml", ops, colours)
            write_svg(ASSETS / f"{name}.svg", ops, colours, f"{family} {label}")
            if slug == spec["default"] or slug in {"true_red", "pangong_blue_duo", "quartz_white", "claret_red", "indium_blue"}:
                write_svg(PREVIEW / f"{name}.svg", ops, colours, label)
            ET.parse(DRAWABLE / f"{name}.xml")
            count += 1
    print(f"wrote {count} drawings")


if __name__ == "__main__":
    main()
