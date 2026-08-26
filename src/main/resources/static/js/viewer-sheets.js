/* ═══════════════════════════════════════════════════════════════════════════
   viewer-sheets.js
   Lazy, per-tab sheet loading for the viewer.

   The /view page now ships only the tab bar + issues panel. Each sheet's grid
   is fetched on first click from /api/view/{sessionId}/sheet/{index} and cached
   client-side, so the page no longer serialises every sheet (~8MB) at once.

   The grid markup below is a faithful port of the old Thymeleaf cell template:
   header cells, formula highlight, critical/warning highlight, indicators,
   tooltips and "Missing" placeholders all render identically.
═══════════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';

    var CTX   = window.VIEWER_CTX || { sessionId: '', activeTab: 0, errorCount: 0, sheets: [] };
    var cache = {};                 // index -> rendered HTML string
    var current = CTX.activeTab || 0;

    // ── Public API (called from inline onclick) ──────────────────────────────
    window.loadSheet         = loadSheet;
    window.reloadActiveSheet = function () { loadSheet(current, true); };
    window.filterGrid        = filterGrid;
    window.clearGridFilter   = clearGridFilter;

    var gridFilterTerm = '';

    document.addEventListener('DOMContentLoaded', function () {
        loadSheet(current);
    });

    // ── Tab switch + load ────────────────────────────────────────────────────
    function loadSheet(idx, force) {
        // Respect the phase gate: never fetch a locked (e.g. Pivot) tab.
        var btn = document.getElementById('tab-btn-' + idx);
        if (btn && btn.classList.contains('sheet-tab-locked')) {
            if (typeof window.lockedPivotClick === 'function') window.lockedPivotClick();
            idx = 0;
        }
        current = idx;
        setActiveTab(idx);

        if (!force && cache[idx] != null) {
            paint(cache[idx]);
            return;
        }

        showState('loading');

        fetch('/api/view/' + encodeURIComponent(CTX.sessionId) + '/sheet/' + idx, {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        })
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (sheet) {
            var html = buildSheetHtml(sheet, idx);
            cache[idx] = html;
            if (current === idx) paint(html);   // ignore if user already switched away
        })
        .catch(function (err) {
            console.error('[viewer-sheets] load failed', err);
            if (current === idx) showState('error');
        });
    }

    function setActiveTab(idx) {
        document.querySelectorAll('.sheet-tab').forEach(function (b) {
            b.classList.remove('active');
        });
        var btn = document.getElementById('tab-btn-' + idx);
        if (btn) btn.classList.add('active');
    }

    function showState(state) {
        var loading = document.getElementById('sheetLoading');
        var error   = document.getElementById('sheetError');
        if (loading) loading.style.display = (state === 'loading') ? 'flex' : 'none';
        if (error)   error.style.display   = (state === 'error')   ? 'flex' : 'none';
    }

    function paint(html) {
        var host = document.getElementById('sheetHost');
        host.innerHTML = html;
        bindTooltipFlip(host);
        applyGridFilter();   // keep the active filter when switching sheets
    }

    // ── Per-sheet text filter ────────────────────────────────────────────────
    function filterGrid(value) {
        gridFilterTerm = (value || '').trim().toLowerCase();
        var clear = document.getElementById('gridFilterClear');
        if (clear) clear.style.display = gridFilterTerm ? 'inline-flex' : 'none';
        applyGridFilter();
    }

    function clearGridFilter() {
        var input = document.getElementById('gridFilter');
        if (input) input.value = '';
        filterGrid('');
    }

    function applyGridFilter() {
        var host  = document.getElementById('sheetHost');
        var table = host ? host.querySelector('table.excel-table tbody') : null;
        var countEl = document.getElementById('gridFilterCount');
        if (!table) { if (countEl) countEl.textContent = ''; return; }

        var rows = table.querySelectorAll('tr');
        var shown = 0, total = 0;

        rows.forEach(function (tr) {
            // Always keep header rows (they carry the column titles) visible.
            var isHeaderRow = tr.querySelector('.header-cell') != null;
            if (isHeaderRow) { tr.classList.remove('row-hidden'); return; }

            total++;
            if (!gridFilterTerm) {
                tr.classList.remove('row-hidden');
                shown++;
                return;
            }
            var match = (tr.textContent || '').toLowerCase().indexOf(gridFilterTerm) !== -1;
            tr.classList.toggle('row-hidden', !match);
            if (match) shown++;
        });

        if (countEl) {
            countEl.textContent = gridFilterTerm
                ? (shown + ' of ' + total + ' rows')
                : '';
        }
    }

    // ── Grid builder (port of the Thymeleaf cell template) ───────────────────
    function buildSheetHtml(sheet, idx) {
        var cols = sheet.colCount;
        var html = '<div class="sheet-panel active">';
        html += '<div class="table-wrapper">';
        html += '<table class="excel-table" id="tbl-' + idx + '">';

        // Header: # + column letters
        html += '<thead><tr class="row-num-header"><th class="row-num-cell corner">#</th>';
        for (var c = 0; c < cols; c++) {
            html += '<th class="col-letter">' + colLetter(c) + '</th>';
        }
        html += '</tr></thead><tbody>';

        var rows = sheet.rows || [];
        for (var r = 0; r < rows.length; r++) {
            html += '<tr><td class="row-num-cell">' + (r + 1) + '</td>';
            var row = rows[r];
            for (var ci = 0; ci < row.length; ci++) {
                html += buildCell(row[ci]);
            }
            html += '</tr>';
        }
        html += '</tbody></table></div>';

        // Stats bar
        html += '<div class="sheet-stats"><span><i class="fa fa-table"></i> ' +
                sheet.rowCount + ' rows \u00D7 ' + sheet.colCount + ' columns</span>';
        if (sheet.sheetName === 'Timesheet') {
            if ((CTX.errorCount || 0) > 0) {
                html += '<span class="ml-2"><span class="badge badge-red">' +
                        '<i class="fa fa-triangle-exclamation"></i> ' + CTX.errorCount +
                        ' validation error(s)</span></span>';
            } else {
                html += '<span class="ml-2"><span class="badge badge-green">' +
                        '<i class="fa fa-circle-check"></i> Valid</span></span>';
            }
        }
        html += '</div></div>';
        return html;
    }

    function buildCell(cell) {
        if (!cell) cell = {};
        var msgs = cell.validationMessages || [];
        var sevs = cell.severities || [];
        var hasMsgs   = msgs.length > 0;
        var isCrit    = sevs.indexOf('CRITICAL') !== -1;
        var isWarn    = sevs.indexOf('WARNING') !== -1;
        var formula   = cell.formula;
        var display   = cell.displayValue || '';
        var type      = cell.cellType || 'BLANK';

        // Cell class (same precedence as the old template)
        var cls = 'excel-cell';
        if (cell.header)      cls = 'excel-cell header-cell';
        else if (isCrit)      cls = 'excel-cell cell-error';
        else if (isWarn)      cls = 'excel-cell cell-warning';
        else if (formula)     cls = 'excel-cell cell-formula';
        if (cell.notApplicable) cls += ' na-cell';

        var title = formula ? formula : (hasMsgs ? msgs.join(' | ') : '');

        var td = '<td class="' + cls + '"' +
                 ' data-type="' + esc(type) + '"' +
                 (formula ? ' data-formula="' + esc(formula) + '"' : '') +
                 (hasMsgs ? ' data-validation="' + esc(msgs.join(' | ')) + '"' : '') +
                 (title ? ' title="' + esc(title) + '"' : '') + '>';

        td += '<span class="cell-content">' + esc(display) + '</span>';

        if (display === '' && hasMsgs) {
            td += '<span class="missing-value">Missing</span>';
        }
        if (formula) {
            td += '<span class="formula-indicator" title="Has formula">' +
                  '<i class="fa fa-function fa-xs"></i></span>';
        }
        if (hasMsgs) {
            td += '<span class="validation-indicator ' + (isCrit ? 'error' : 'warning') + '">' +
                  '<i class="fa ' + (isCrit ? 'fa-xmark' : 'fa-exclamation') + ' fa-xs"></i></span>';
        }

        // Tooltip (rendered when there's a formula or validation message)
        if (formula || hasMsgs) {
            td += '<div class="cell-tooltip">';
            if (type === 'NUMERIC' && display.indexOf('(') !== -1) {
                td += '<div class="tooltip-date"><i class="fa fa-calendar-day"></i> ' +
                      '<span>' + esc(display) + '</span></div>';
            }
            if (hasMsgs) {
                td += '<div><div class="tooltip-title">Validation Issue</div>';
                for (var i = 0; i < msgs.length; i++) {
                    var crit = sevs[i] === 'CRITICAL';
                    td += '<div class="' + (crit ? 'tooltip-error' : 'tooltip-warning') + '">' +
                          '<i class="fa ' + (crit ? 'fa-circle-xmark' : 'fa-triangle-exclamation') + '"></i> ' +
                          '<span>' + esc(msgs[i]) + '</span></div>';
                }
                td += '</div>';
            }
            td += '</div>';
        }

        td += '</td>';
        return td;
    }

    // ── Tooltip flip near viewport bottom (delegated, since cells are dynamic) ─
    function bindTooltipFlip(host) {
        host.addEventListener('mouseenter', function (e) {
            var cell = e.target.closest ? e.target.closest('.excel-cell') : null;
            if (!cell) return;
            var tip = cell.querySelector('.cell-tooltip');
            if (!tip) return;
            var rect = cell.getBoundingClientRect();
            if (window.innerHeight - rect.bottom < 120) {
                tip.style.top = 'auto'; tip.style.bottom = '100%';
            } else {
                tip.style.top = '100%'; tip.style.bottom = 'auto';
            }
        }, true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    function colLetter(col) {
        var s = ''; col++;
        while (col > 0) {
            var rem = (col - 1) % 26;
            s = String.fromCharCode(65 + rem) + s;
            col = Math.floor((col - 1) / 26);
        }
        return s;
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
}());
