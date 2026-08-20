/*
 ============================================================================
 leaveplanner-viewer.js

 Excel-like renderer for Leave Planner.

 Features
 --------
 ✔ Lazy sheet loading
 ✔ Uses existing SheetDto API
 ✔ Merged cells
 ✔ Column widths
 ✔ Row heights
 ✔ Borders
 ✔ Fonts
 ✔ Colors
 ✔ Alignment

 ============================================================================
*/

(function () {

    "use strict";

    const CTX = window.LEAVE_PLANNER_CTX || {};

    const cache = {};

    let currentSheet = 0;

    let mergedLookup = {};

    let mergedMaster = {};

    let currentDto = null;

    window.loadLeavePlannerSheet = loadSheet;

    document.addEventListener("DOMContentLoaded", function () {

        if (CTX.activeTab == null) {

            currentSheet = 0;

        } else {

            currentSheet = CTX.activeTab;
        }

        loadSheet(currentSheet);

    });

    function loadSheet(sheetIndex, force) {

        currentSheet = sheetIndex;

        activateTab(sheetIndex);

        if (!force && cache[sheetIndex]) {

            render(cache[sheetIndex]);

            return;
        }

        showLoading();

        fetch(
            "/api/view/" +
            encodeURIComponent(CTX.sessionId) +
            "/sheet/" +
            sheetIndex,
            {
                credentials: "same-origin",
                headers: {
                    Accept: "application/json"
                }
            }
        )
            .then(function (r) {

                if (!r.ok) {

                    throw new Error("Unable to load sheet");

                }

                return r.json();

            })
            .then(function (dto) {

                currentDto = dto;

                preprocessMergedRegions(dto);

                const html = buildSheet(dto);

                cache[sheetIndex] = html;

                render(html);

            })
            .catch(function (e) {

                console.error(e);

                showError();

            });

    }

    function activateTab(index) {

        document
            .querySelectorAll(".sheet-tab")
            .forEach(function (t) {

                t.classList.remove("active");

            });

        const btn =
            document.getElementById("tab-btn-" + index);

        if (btn) {

            btn.classList.add("active");

        }

    }

    function render(html) {

        hideLoading();

        const host =
            document.getElementById("sheetHost");

            if (!host) {
                return;
            }

        host.innerHTML = html;

    }

    function showLoading() {

        const host =
            document.getElementById("sheetHost");

            if (!host) {
                            return;
                        }

        host.innerHTML =
            "<div class='lp-loading'>" +
            "<i class='fa fa-spinner fa-spin'></i>" +
            "<span>Loading workbook...</span>" +
            "</div>";

    }

    function showError() {

        const host =
            document.getElementById("sheetHost");

            if (!host) {
                            return;
                        }

        host.innerHTML =
            "<div class='lp-error'>" +
            "<i class='fa fa-circle-xmark'></i>" +
            "<span>Unable to load workbook.</span>" +
            "</div>";

    }

    function hideLoading() {
    }

    function preprocessMergedRegions(dto) {

        mergedLookup = {};

        mergedMaster = {};

        if (!dto.mergedRegions) {

            return;

        }

        dto.mergedRegions.forEach(function (r) {

            const masterKey =
                r.firstRow + "_" + r.firstColumn;

            mergedMaster[masterKey] = {

                rowspan:
                    r.lastRow - r.firstRow + 1,

                colspan:
                    r.lastColumn - r.firstColumn + 1

            };

            for (
                let rr = r.firstRow;
                rr <= r.lastRow;
                rr++
            ) {

                for (
                    let cc = r.firstColumn;
                    cc <= r.lastColumn;
                    cc++
                ) {

                    if (
                        rr === r.firstRow &&
                        cc === r.firstColumn
                    ) {
                        continue;
                    }

                    mergedLookup[
                        rr + "_" + cc
                    ] = true;

                }

            }

        });

    }

    function isMergedChild(row, col) {

        return mergedLookup[
            row + "_" + col
        ] === true;

    }

    function getMaster(row, col) {

        return mergedMaster[
            row + "_" + col
        ];

    }

        function buildSheet(dto) {

            let html = "";

            html +=
                "<div class='lp-sheet'>";

            html +=
                "<div class='lp-table-wrapper'>";

            html +=
                "<table class='lp-table'>";

            html += buildColGroup(dto);

            html += buildRows(dto);

            html += "</table>";

            html += "</div>";

            html += "</div>";

            return html;

        }

    function buildColGroup(dto) {

        let html = "<colgroup>";

        const widths = dto.columnWidths || [];

        const totalCols = dto.colCount || 0;

        for (let c = 0; c < totalCols; c++) {

            const excelWidth = widths[c];

            let cssWidth = 100;

            if (excelWidth && excelWidth > 0) {

                /*
                 * Excel width is measured in 1/256th of a character.
                 * Convert to approximate pixels.
                 */

                cssWidth = Math.max(
                    40,
                    Math.round(excelWidth * 0.027)
                );

            }

            html +=
                '<col style="width:' +
                cssWidth +
                'px">';

        }

        html += "</colgroup>";

        return html;

    }

    function buildRows(dto) {

        let html = "<tbody>";

        const rowHeights =
            dto.rowHeights || [];

        const rows =
            dto.rows || [];

        for (let r = 0; r < rows.length; r++) {

            const row =
                rows[r];

            let style = "";

            if (
                rowHeights.length > r &&
                rowHeights[r]
            ) {

                const cssHeight =
                    Math.max(
                        18,
                        Math.round(rowHeights[r] / 20)
                    );

            }

            html += "<tr" + style + ">";

            for (let c = 0; c < row.length; c++) {

                if (isMergedChild(r, c)) {

                    continue;

                }

                html += buildCell(
                    row[c],
                    r,
                    c
                );

            }

            html += "</tr>";

        }

        html += "</tbody>";

        return html;

    }


    function buildCell(cell, row, col) {

        if (!cell) {

            return "<td></td>";

        }

        const master =
            getMaster(row, col);

        let attrs = "";

        if (master) {

            if (master.rowspan > 1) {

                attrs +=
                    ' rowspan="' +
                    master.rowspan +
                    '"';

            }

            if (master.colspan > 1) {

                attrs +=
                    ' colspan="' +
                    master.colspan +
                    '"';

            }

        }

        const styles =
            buildCellStyle(cell);

        const classes =
            buildCellClasses(cell);

//        const value =
//            escapeHtml(
//                cell.displayValue || ""
//            );

        let value =
            cell.displayValue || "";

        if (value === null) {

            value = "";

        }

        value = escapeHtml(value);

        if (value === "") {

            value = "&nbsp;";

        }

        let tooltip = "";

        if (cell.formula) {

            tooltip =
                ' title="' +
                escapeHtml(cell.formula) +
                '"';

        }

        return (
            "<td" +
            attrs +
            tooltip +
            ' class="' +
            classes +
            '" style="' +
            styles +
            '">' +
            value +
            "</td>"
        );

    }


    function buildCellClasses(cell) {

        let cls = "lp-cell";

        if (cell.isHeader) {

            cls += " lp-header";

        }

        if (cell.highestSeverity === "WARNING") {

            cls += " lp-warning";

        }

        if (cell.highestSeverity === "CRITICAL") {

            cls += " lp-critical";

        }

        if (
            cell.employeeIssue === true
        ) {

            cls += " lp-employee";

        }

        return cls;

    }

    function escapeHtml(value) {

        return value

            .replace(/&/g, "&amp;")

            .replace(/</g, "&lt;")

            .replace(/>/g, "&gt;")

            .replace(/"/g, "&quot;")

            .replace(/'/g, "&#39;");

    }

    function buildCellStyle(cell) {

        let styles = [];

        /*
         * Background Color
         */

        if (cell.backgroundColor) {

            styles.push(
                "background-color:" +
                normalizeColor(cell.backgroundColor)
            );

        }

        /*
         * Font Color
         */

        if (cell.fontColor) {

            styles.push(
                "color:" +
                normalizeColor(cell.fontColor)
            );

        }

        /*
         * Font Size
         */

        if (cell.fontSize) {

            styles.push(
                "font-size:" +
                cell.fontSize +
                "pt"
            );

        }

        /*
         * Font Weight
         */

        if (cell.bold) {

            styles.push("font-weight:bold");

        }

        /*
         * Italic
         */

        if (cell.italic) {

            styles.push("font-style:italic");

        }

        /*
         * Horizontal Alignment
         */

        styles.push(
            horizontalAlignment(
                cell.horizontalAlignment
            )
        );

        /*
         * Vertical Alignment
         */

        styles.push(
            verticalAlignment(
                cell.verticalAlignment
            )
        );

        /*
         * Borders
         */

        styles.push(
            borderCss(
                "top",
                cell.borderTop
            )
        );

        styles.push(
            borderCss(
                "bottom",
                cell.borderBottom
            )
        );

        styles.push(
            borderCss(
                "left",
                cell.borderLeft
            )
        );

        styles.push(
            borderCss(
                "right",
                cell.borderRight
            )
        );

        /*
         * Wrap
         */

        styles.push(
            "white-space:pre-wrap"
        );

        styles.push(
            "word-break:break-word"
        );

        return styles
            .filter(Boolean)
            .join(";");

    }

    function borderCss(side, style) {

        if (!style) {

            return "";

        }

        style =
            style.toUpperCase();

        switch (style) {

            case "THICK":

                return "border-" +
                    side +
                    ":2px solid #444";

            case "MEDIUM":

                return "border-" +
                    side +
                    ":1.5px solid #666";

            case "DASHED":

                return "border-" +
                    side +
                    ":1px dashed #777";

            case "DOTTED":

                return "border-" +
                    side +
                    ":1px dotted #777";

            case "DOUBLE":

                return "border-" +
                    side +
                    ":3px double #444";

            case "NONE":

                return "";

            default:

                return "border-" +
                    side +
                    ":1px solid #bdbdbd";

        }

    }

    function horizontalAlignment(value) {

        if (!value) {

            return "";

        }

        value =
            value.toUpperCase();

        switch (value) {

            case "CENTER":

                return "text-align:center";

            case "RIGHT":

                return "text-align:right";

            case "FILL":

                return "text-align:justify";

            case "JUSTIFY":

                return "text-align:justify";

            default:

                return "text-align:left";

        }

    }

    function verticalAlignment(value) {

        if (!value) {

            return "";

        }

        value =
            value.toUpperCase();

        switch (value) {

            case "CENTER":

                return "vertical-align:middle";

            case "BOTTOM":

                return "vertical-align:bottom";

            default:

                return "vertical-align:top";

        }

    }

    function normalizeColor(color) {

        if (!color) {

            return "";

        }

        color =
            color.replace("#", "");

        /*
         * Remove Alpha
         */

        if (color.length === 8) {

            color =
                color.substring(2);

        }

        return "#" + color;

    }

})();

