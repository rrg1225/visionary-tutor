package com.visionary.rag;

import com.visionary.rag.VectorDbService.KnowledgeFragment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Treats retrieved documents as untrusted data and quarantines instruction-like chunks. */
@Slf4j
@Component
public class RetrievedContentGuard {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|print|return|exfiltrate).{0,40}(system|developer)\\s+prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(in|a|an)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script[\\s>]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("忽略.{0,12}(之前|以上|系统).{0,8}(指令|提示词)"),
            Pattern.compile("(泄露|输出|返回).{0,12}(系统提示词|开发者消息|密钥)")
    );

    public List<KnowledgeFragment> filter(List<KnowledgeFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<KnowledgeFragment> safe = fragments.stream().filter(fragment -> !isSuspicious(fragment)).toList();
        int rejected = fragments.size() - safe.size();
        if (rejected > 0) {
            log.warn("Quarantined {} retrieved chunk(s) containing instruction-like content", rejected);
        }
        return safe;
    }

    public boolean isSuspicious(KnowledgeFragment fragment) {
        if (fragment == null || fragment.content() == null) {
            return false;
        }
        String content = fragment.content().strip();
        if (content.isEmpty()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return INJECTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}
