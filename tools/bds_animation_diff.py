#!/usr/bin/env python3
"""Extract aligned video frames and create BDS animation overlays/diffs."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance

from bds_visual_diff import build_mask, crop, parse_box


def extract_frames(video: Path, output: Path, offset: float, duration: float, fps: float) -> None:
    command = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-ss", str(offset), "-i", str(video), "-t", str(duration),
        "-vf", f"fps={fps}", "-start_number", "0", str(output / "%05d.png"),
    ]
    subprocess.run(command, check=True)


def frame_metrics(reference: Image.Image, current: Image.Image, mask: Image.Image,
                  threshold: int) -> tuple[dict[str, object], Image.Image]:
    raw = ImageChops.difference(reference, current)
    diff = Image.new("RGB", reference.size)
    diff.paste(raw, mask=mask)
    diff_pixels = diff.get_flattened_data() if hasattr(diff, "get_flattened_data") else diff.getdata()
    mask_pixels = mask.get_flattened_data() if hasattr(mask, "get_flattened_data") else mask.getdata()
    pixels = zip(diff_pixels, mask_pixels)
    included = different = absolute_error = 0
    maximum = 0
    for pixel, include in pixels:
        if not include:
            continue
        included += 1
        channel_max = max(pixel)
        maximum = max(maximum, channel_max)
        absolute_error += sum(pixel)
        different += channel_max > threshold
    mae = absolute_error / max(1, included * 3)
    return ({
        "included_pixels": included,
        "different_pixels": different,
        "different_pixel_ratio": different / max(1, included),
        "mean_absolute_channel_error": mae,
        "similarity": 1.0 - mae / 255.0,
        "maximum_channel_error": maximum,
        "difference_bounding_box": list(diff.getbbox()) if diff.getbbox() else None,
    }, diff)


def make_contact_sheet(frames: list[Image.Image], labels: list[str], output: Path,
                       columns: int = 5) -> None:
    if not frames:
        return
    thumb_width = 216
    thumb_height = round(frames[0].height * thumb_width / frames[0].width)
    label_height = 28
    rows = (len(frames) + columns - 1) // columns
    sheet = Image.new("RGB", (thumb_width * columns, (thumb_height + label_height) * rows), "white")
    draw = ImageDraw.Draw(sheet)
    for index, (frame, label) in enumerate(zip(frames, labels)):
        x = index % columns * thumb_width
        y = index // columns * (thumb_height + label_height)
        sheet.paste(frame.resize((thumb_width, thumb_height)), (x, y))
        draw.text((x + 5, y + thumb_height + 5), label, fill="black")
    sheet.save(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--reference-offset", type=float, default=0.0)
    parser.add_argument("--current-offset", type=float, default=0.0)
    parser.add_argument("--duration", type=float, required=True)
    parser.add_argument("--fps", type=float, default=60.0)
    parser.add_argument("--crop", type=parse_box)
    parser.add_argument("--reference-crop", type=parse_box)
    parser.add_argument("--current-crop", type=parse_box)
    parser.add_argument("--mask", type=parse_box, action="append", default=[])
    parser.add_argument("--pixel-threshold", type=int, default=8)
    parser.add_argument("--contact-every", type=int, default=6,
                        help="include every Nth frame in contact sheets (default: 6)")
    args = parser.parse_args()

    if shutil.which("ffmpeg") is None:
        parser.error("ffmpeg was not found")
    if args.crop and (args.reference_crop or args.current_crop):
        parser.error("--crop cannot be combined with separate crop options")
    if (args.reference_crop is None) != (args.current_crop is None):
        parser.error("provide both --reference-crop and --current-crop")
    if args.duration <= 0 or args.fps <= 0 or args.contact_every <= 0:
        parser.error("duration, fps, and contact-every must be positive")
    if not 0 <= args.pixel_threshold <= 255:
        parser.error("--pixel-threshold must be between 0 and 255")

    reference_crop = args.reference_crop or args.crop
    current_crop = args.current_crop or args.crop
    args.output_dir.mkdir(parents=True, exist_ok=True)
    overlay_dir = args.output_dir / "overlay"
    diff_dir = args.output_dir / "diff-enhanced"
    overlay_dir.mkdir(exist_ok=True)
    diff_dir.mkdir(exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="bds-animation-diff-") as temporary:
        root = Path(temporary)
        reference_frames = root / "reference"
        current_frames = root / "current"
        reference_frames.mkdir()
        current_frames.mkdir()
        extract_frames(args.reference, reference_frames, args.reference_offset, args.duration, args.fps)
        extract_frames(args.current, current_frames, args.current_offset, args.duration, args.fps)
        reference_paths = sorted(reference_frames.glob("*.png"))
        current_paths = sorted(current_frames.glob("*.png"))
        frame_count = min(len(reference_paths), len(current_paths))
        if frame_count == 0:
            parser.error("no aligned frames were extracted")

        records: list[dict[str, object]] = []
        contact_overlays: list[Image.Image] = []
        contact_diffs: list[Image.Image] = []
        contact_labels: list[str] = []
        mask = None
        for index, (reference_path, current_path) in enumerate(
            zip(reference_paths[:frame_count], current_paths[:frame_count])
        ):
            reference = crop(Image.open(reference_path).convert("RGB"), reference_crop)
            current = crop(Image.open(current_path).convert("RGB"), current_crop)
            if reference.size != current.size:
                parser.error(f"cropped sizes differ: {reference.size} and {current.size}")
            if mask is None:
                mask = build_mask(reference.size, args.mask)
            metrics, diff = frame_metrics(reference, current, mask, args.pixel_threshold)
            time_seconds = index / args.fps
            metrics.update({"frame": index, "time_seconds": time_seconds})
            records.append(metrics)
            overlay = Image.blend(reference, current, 0.5)
            enhanced = ImageEnhance.Contrast(diff).enhance(3.0)
            overlay.save(overlay_dir / f"{index:05d}.png")
            enhanced.save(diff_dir / f"{index:05d}.png")
            if index % args.contact_every == 0:
                contact_overlays.append(overlay)
                contact_diffs.append(enhanced)
                contact_labels.append(f"{time_seconds * 1000:.0f} ms")

    aggregate = {
        "reference": str(args.reference.resolve()),
        "current": str(args.current.resolve()),
        "reference_offset": args.reference_offset,
        "current_offset": args.current_offset,
        "duration": args.duration,
        "fps": args.fps,
        "frame_count": len(records),
        "reference_crop": list(reference_crop) if reference_crop else None,
        "current_crop": list(current_crop) if current_crop else None,
        "masks": [list(box) for box in args.mask],
        "pixel_threshold": args.pixel_threshold,
        "mean_similarity": sum(float(record["similarity"]) for record in records) / len(records),
        "mean_absolute_channel_error": sum(
            float(record["mean_absolute_channel_error"]) for record in records
        ) / len(records),
        "frames": records,
    }
    (args.output_dir / "metrics.json").write_text(
        json.dumps(aggregate, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    make_contact_sheet(contact_overlays, contact_labels, args.output_dir / "overlay-contact.png")
    make_contact_sheet(contact_diffs, contact_labels, args.output_dir / "diff-contact.png")
    print(json.dumps({key: value for key, value in aggregate.items() if key != "frames"}, indent=2))


if __name__ == "__main__":
    main()
