package com.mediaurl.manager

object ScriptManager {

    val PRESET_AUTO_PLAY = """
        (function() {
            let played = 0;
            document.querySelectorAll('video, audio').forEach(v => {
                try {
                    v.muted = true;
                    v.play();
                    played++;
                } catch(e) {}
            });
            document.querySelectorAll('button[class*="play"], div[class*="play"], .vjs-big-play-button, .jw-display-icon-container').forEach(b => {
                try { b.click(); played++; } catch(e) {}
            });
            return 'Triggered auto-play on ' + played + ' media elements';
        })();
    """.trimIndent()

    val PRESET_CLICK_SELECTOR = """
        (function() {
            const selector = 'button.play, .jw-display-icon-container, .vjs-play-control';
            const el = document.querySelector(selector);
            if (el) {
                el.click();
                return 'Clicked element: ' + el.tagName + ' (' + selector + ')';
            }
            return 'Element not found for selector: ' + selector;
        })();
    """.trimIndent()

    val PRESET_REMOVE_OVERLAYS = """
        (function() {
            let count = 0;
            document.querySelectorAll('div[class*="overlay"], div[class*="popup"], div[class*="modal"], div[id*="ad"], iframe[id*="aswift"], iframe[src*="ad"]').forEach(e => {
                try { e.remove(); count++; } catch(e) {}
            });
            return 'Removed ' + count + ' overlays / ads';
        })();
    """.trimIndent()

    val PRESET_EXTRACT_TAGS = """
        (function() {
            const list = [];
            document.querySelectorAll('video, audio, source, iframe').forEach(el => {
                const src = el.src || el.getAttribute('src');
                if (src) list.push(el.tagName + ': ' + src);
            });
            return list.length > 0 ? list.join('\n') : 'No media elements found in DOM';
        })();
    """.trimIndent()

    val PRESET_GET_COOKIES = """
        (function() {
            return document.cookie || 'No cookies accessible';
        })();
    """.trimIndent()
}
