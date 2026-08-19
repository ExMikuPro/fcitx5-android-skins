#!/usr/bin/env python3
"""Create reproducible pixel diffs for BDS keyboard screenshots.

The same crop is applied to both full-screen captures by default.  Separate
crops are also supported when two devices have different system insets; both
resulting crops must still have the same dimensions.  Masks use coordinates
relative to the cropped image and are excluded from every reported metric.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageChops, ImageDraw, ImageEnhance


Box = tuple[int, int, int, int]


def parse_box(value: str) -> Box:
    try:
        x, y, width, height = (int(part) for part in value.split(","))
    except (TypeError, ValueError) as error:
        raise argparse.ArgumentTypeError("expected x,y,width,height") from error
    if width <= 0 or height <= 0:
        raise argparse.ArgumentTypeError("width and height must be positive")
    return x, y, width, height


def pil_box(box: Box) -> Box:
    x, y, width, height = box
    return x, y, x + width, y + height


def crop(image: Image.Image, box: Box | None) -> Image.Image:
    return image.crop(pil_box(box)) if box else image.copy()


def build_mask(size: tuple[int, int], boxes: Iterable[Box]) -> Image.Image:
    mask = Image.new("1", size, 1)
    draw = ImageDraw.Draw(mask)
    for box in boxes:
        draw.rectangle(pil_box(box), fill=0)
    return mask


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--crop", type=parse_box, help="common x,y,width,height crop")
    parser.add_argument("--reference-crop", type=parse_box)
    parser.add_argument("--current-crop", type=parse_box)
    parser.add_argument(
        "--mask", type=parse_box, action="append", default=[],
        help="repeatable x,y,width,height mask relative to the crop"
    )
    parser.add_argument(
        "--pixel-threshold", type=int, default=0,
        help="a pixel differs when any RGB channel exceeds this value"
    )
    args = parser.parse_args()

    if args.crop and (args.reference_crop or args.current_crop):
        parser.error("--crop cannot be combined with separate crop options")
    reference_box = args.reference_crop or args.crop
    current_box = args.current_crop or args.crop
    if (args.reference_crop is None) != (args.current_crop is None):
        parser.error("provide both --reference-crop and --current-crop")
    if not 0 <= args.pixel_threshold <= 255:
        parser.error("--pixel-threshold must be between 0 and 255")

    reference_full = Image.open(args.reference).convert("RGB")
    current_full = Image.open(args.current).convert("RGB")
    reference = crop(reference_full, reference_box)
    current = crop(current_full, current_box)
    if reference.size != current.size:
        parser.error(
            f"cropped image sizes differ: reference={reference.size}, current={current.size}"
        )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    reference.save(args.output_dir / "reference.png")
    current.save(args.output_dir / "current.png")

    mask = build_mask(reference.size, args.mask)
    raw_diff = ImageChops.difference(reference, current)
    diff = Image.new("RGB", reference.size)
    diff.paste(raw_diff, mask=mask)
    diff.save(args.output_dir / "diff.png")
    ImageEnhance.Contrast(diff).enhance(3.0).save(args.output_dir / "diff-enhanced.png")
    Image.blend(reference, current, 0.5).save(args.output_dir / "overlay.png")
    mask.convert("L").save(args.output_dir / "mask.png")

    histogram = diff.histogram()
    channel_pixels = sum(histogram[:256])
    absolute_error_sum = sum(
        value * count
        for channel in range(3)
        for value, count in enumerate(histogram[channel * 256:(channel + 1) * 256])
    )
    max_error = max(
        value
        for channel in range(3)
        for value, count in enumerate(histogram[channel * 256:(channel + 1) * 256])
        if count
    )

    included = 0
    different = 0
    threshold = args.pixel_threshold
    for pixel, included_pixel in zip(diff.getdata(), mask.getdata()):
        if not included_pixel:
            continue
        included += 1
        if max(pixel) > threshold:
            different += 1

    mean_channel_error = absolute_error_sum / max(1, included * 3)
    metrics = {
        "width": reference.width,
        "height": reference.height,
        "reference_full_size": list(reference_full.size),
        "current_full_size": list(current_full.size),
        "reference_crop": list(reference_box) if reference_box else None,
        "current_crop": list(current_box) if current_box else None,
        "cropped_size": list(reference.size),
        "masks": [list(box) for box in args.mask],
        "included_pixels": included,
        "masked_pixels": reference.width * reference.height - included,
        "pixel_threshold": threshold,
        "different_pixels": different,
        "different_pixel_ratio": different / max(1, included),
        "difference_bounding_box": list(diff.getbbox()) if diff.getbbox() else None,
        "mean_absolute_channel_error": mean_channel_error,
        "mae": mean_channel_error,
        "maximum_channel_error": max_error,
        "max_error": max_error,
        "mean_error_similarity": 1.0 - mean_channel_error / 255.0,
        "similarity": 1.0 - mean_channel_error / 255.0,
        "histogram_channel_pixels": channel_pixels,
    }
    (args.output_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
