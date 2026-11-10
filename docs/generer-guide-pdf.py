from __future__ import annotations

import html
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)
from reportlab.platypus.tableofcontents import TableOfContents


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "guide-complet-identity-service.md"
OUTPUT = ROOT / "docs" / "guide-complet-identity-service.pdf"
NAVY = colors.HexColor("#142B4A")
BLUE = colors.HexColor("#2363A5")
LIGHT_BLUE = colors.HexColor("#EAF1F8")
PALE = colors.HexColor("#F4F7FB")
TEXT = colors.HexColor("#253247")


def markup(text: str) -> str:
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"\*(.+?)\*", r"<i>\1</i>", text)
    text = re.sub(
        r"`(.+?)`",
        r'<font name="Courier" color="#174A73">\1</font>',
        text,
    )
    return text


def build_styles():
    font_dir = Path(__import__("reportlab").__file__).resolve().parent / "fonts"
    pdfmetrics.registerFont(TTFont("Vera", str(font_dir / "Vera.ttf")))
    pdfmetrics.registerFont(TTFont("Vera-Bold", str(font_dir / "VeraBd.ttf")))
    pdfmetrics.registerFont(TTFont("Vera-Italic", str(font_dir / "VeraIt.ttf")))
    pdfmetrics.registerFont(TTFont("Vera-BoldItalic", str(font_dir / "VeraBI.ttf")))
    pdfmetrics.registerFontFamily(
        "Vera",
        normal="Vera",
        bold="Vera-Bold",
        italic="Vera-Italic",
        boldItalic="Vera-BoldItalic",
    )
    base = getSampleStyleSheet()
    return {
        "cover_title": ParagraphStyle(
            "CoverTitle",
            parent=base["Title"],
            fontName="Vera-Bold",
            fontSize=27,
            leading=34,
            textColor=NAVY,
            alignment=TA_CENTER,
            spaceAfter=18,
        ),
        "cover_subtitle": ParagraphStyle(
            "CoverSubtitle",
            parent=base["Normal"],
            fontName="Vera",
            fontSize=12,
            leading=18,
            textColor=BLUE,
            alignment=TA_CENTER,
        ),
        "h1": ParagraphStyle(
            "GuideHeading1",
            parent=base["Heading1"],
            fontName="Vera-Bold",
            fontSize=19,
            leading=24,
            textColor=NAVY,
            spaceBefore=13,
            spaceAfter=9,
            keepWithNext=True,
        ),
        "h2": ParagraphStyle(
            "GuideHeading2",
            parent=base["Heading2"],
            fontName="Vera-Bold",
            fontSize=13.5,
            leading=17,
            textColor=BLUE,
            spaceBefore=10,
            spaceAfter=5,
            keepWithNext=True,
        ),
        "h3": ParagraphStyle(
            "GuideHeading3",
            parent=base["Heading3"],
            fontName="Vera-Bold",
            fontSize=10.5,
            leading=14,
            textColor=NAVY,
            spaceBefore=8,
            spaceAfter=4,
            keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "GuideBody",
            parent=base["BodyText"],
            fontName="Vera",
            fontSize=9.2,
            leading=13.2,
            textColor=TEXT,
            spaceAfter=6,
            splitLongWords=True,
        ),
        "bullet": ParagraphStyle(
            "GuideBullet",
            parent=base["BodyText"],
            fontName="Vera",
            fontSize=9.1,
            leading=12.8,
            textColor=TEXT,
            leftIndent=13,
            firstLineIndent=-9,
            spaceAfter=3,
            splitLongWords=True,
        ),
        "code": ParagraphStyle(
            "GuideCode",
            fontName="Courier",
            fontSize=7.6,
            leading=10,
            textColor=colors.HexColor("#17354F"),
            backColor=PALE,
            borderColor=colors.HexColor("#D7E1EC"),
            borderWidth=0.5,
            borderPadding=7,
            leftIndent=6,
            rightIndent=6,
            spaceBefore=4,
            spaceAfter=8,
        ),
        "table": ParagraphStyle(
            "GuideTable",
            parent=base["BodyText"],
            fontName="Vera",
            fontSize=7.4,
            leading=9.8,
            textColor=TEXT,
            splitLongWords=True,
        ),
        "table_header": ParagraphStyle(
            "GuideTableHeader",
            parent=base["BodyText"],
            fontName="Vera-Bold",
            fontSize=7.5,
            leading=9.8,
            textColor=colors.white,
            splitLongWords=True,
        ),
        "toc_title": ParagraphStyle(
            "GuideTOCTitle",
            parent=base["Heading1"],
            fontName="Vera-Bold",
            fontSize=20,
            leading=25,
            textColor=NAVY,
            spaceAfter=15,
        ),
        "toc1": ParagraphStyle(
            "GuideTOC1",
            fontName="Helvetica-Bold",
            fontSize=9.5,
            leading=13,
            textColor=NAVY,
            leftIndent=0,
            firstLineIndent=0,
            spaceBefore=3,
        ),
        "toc2": ParagraphStyle(
            "GuideTOC2",
            fontName="Vera",
            fontSize=8.2,
            leading=10.5,
            textColor=TEXT,
            leftIndent=12,
            firstLineIndent=0,
        ),
    }


class GuideDocument(SimpleDocTemplate):
    def afterFlowable(self, flowable):
        if not isinstance(flowable, Paragraph):
            return
        if flowable.style.name not in ("GuideHeading2", "GuideHeading3"):
            return
        level = 0 if flowable.style.name == "GuideHeading2" else 1
        text = flowable.getPlainText()
        self.notify("TOCEntry", (level, text, self.page))


def footer(canvas, doc):
    canvas.saveState()
    if doc.page > 1:
        width, _ = A4
        canvas.setStrokeColor(colors.HexColor("#D7E1EC"))
        canvas.setLineWidth(0.5)
        canvas.line(18 * mm, 15 * mm, width - 18 * mm, 15 * mm)
        canvas.setFont("Vera", 8)
        canvas.setFillColor(colors.HexColor("#68788D"))
        canvas.drawString(18 * mm, 10 * mm, "Identity Service · Guide technique")
        canvas.drawRightString(width - 18 * mm, 10 * mm, f"Page {doc.page}")
    canvas.restoreState()


def table_rows(lines, styles):
    rows = []
    for line in lines:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if cells and all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in cells):
            continue
        style = styles["table_header"] if not rows else styles["table"]
        rows.append([Paragraph(markup(cell), style) for cell in cells])

    if not rows:
        return None
    columns = max(len(row) for row in rows)
    for row in rows:
        row.extend([Paragraph("", styles["table"])] * (columns - len(row)))

    available_width = A4[0] - 36 * mm
    if columns == 2:
        first_width = available_width * 0.32
    elif columns >= 4:
        first_width = available_width * 0.22
    else:
        first_width = available_width * 0.27
    widths = [first_width] + [(available_width - first_width) / (columns - 1)] * (columns - 1)

    table = Table(rows, colWidths=widths, repeatRows=1, hAlign="LEFT", splitByRow=1)
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), NAVY),
                ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#CFD9E4")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, PALE]),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 5),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5),
                ("TOPPADDING", (0, 0), (-1, -1), 4),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
            ]
        )
    )
    return table


def parse_markdown(text, styles):
    lines = text.splitlines()
    if lines and lines[0].startswith("# "):
        lines = lines[1:]

    story = []
    index = 0
    while index < len(lines):
        raw = lines[index]
        line = raw.strip()
        if not line or line == "---":
            index += 1
            continue

        if line.startswith("```"):
            index += 1
            block = []
            while index < len(lines) and not lines[index].strip().startswith("```"):
                block.append(lines[index])
                index += 1
            story.append(Preformatted("\n".join(block), styles["code"], maxLineLength=100))
            index += 1
            continue

        if line.startswith("|"):
            block = []
            while index < len(lines) and lines[index].strip().startswith("|"):
                block.append(lines[index].strip())
                index += 1
            rendered_table = table_rows(block, styles)
            if rendered_table:
                story.extend([rendered_table, Spacer(1, 7)])
            continue

        if line.startswith("#"):
            match = re.match(r"^(#{1,3})\s+(.*)$", line)
            if match:
                level = len(match.group(1))
                style = styles[f"h{level}"]
                story.append(Paragraph(markup(match.group(2)), style))
                index += 1
                continue

        if line.startswith("- "):
            story.append(Paragraph("&#8226; " + markup(line[2:]), styles["bullet"]))
            index += 1
            continue

        if re.match(r"^\d+\.\s+", line):
            story.append(Paragraph(markup(line), styles["bullet"]))
            index += 1
            continue

        if line.startswith("> "):
            story.append(
                Paragraph(
                    markup(line[2:]),
                    ParagraphStyle(
                        "GuideCallout",
                        parent=styles["body"],
                        leftIndent=10,
                        borderColor=BLUE,
                        borderWidth=1,
                        borderPadding=6,
                        backColor=LIGHT_BLUE,
                    ),
                )
            )
            index += 1
            continue

        paragraph = [line]
        index += 1
        while index < len(lines):
            next_line = lines[index].strip()
            if (
                not next_line
                or next_line == "---"
                or next_line.startswith(("#", "|", "```", "- ", "> "))
                or re.match(r"^\d+\.\s+", next_line)
            ):
                break
            paragraph.append(next_line)
            index += 1
        story.append(Paragraph(markup(" ".join(paragraph)), styles["body"]))

    return story


def main():
    styles = build_styles()
    markdown = SOURCE.read_text(encoding="utf-8")
    title = markdown.splitlines()[0].removeprefix("# ").strip()
    subtitle = "Documentation fonctionnelle et technique"

    toc = TableOfContents()
    toc.levelStyles = [styles["toc1"], styles["toc2"]]
    toc.dotsMinLevel = 0

    story = [
        Spacer(1, 46 * mm),
        Paragraph(markup(title), styles["cover_title"]),
        Spacer(1, 5 * mm),
        Paragraph(subtitle, styles["cover_subtitle"]),
        Spacer(1, 4 * mm),
        Paragraph("Architecture · API · sécurité · persistance · exploitation", styles["cover_subtitle"]),
        PageBreak(),
        Paragraph("Sommaire", styles["toc_title"]),
        toc,
        PageBreak(),
    ]
    story.extend(parse_markdown(markdown, styles))

    document = GuideDocument(
        str(OUTPUT),
        pagesize=A4,
        rightMargin=18 * mm,
        leftMargin=18 * mm,
        topMargin=17 * mm,
        bottomMargin=21 * mm,
        title=title,
        author="Documentation du projet Identity Service",
        subject="Explication détaillée des composants, méthodes et choix du projet",
    )
    document.multiBuild(story, maxPasses=5, onFirstPage=footer, onLaterPages=footer)
    print(f"PDF généré : {OUTPUT}")


if __name__ == "__main__":
    main()
