package bridge

import (
	"errors"
	"regexp"
)

var (
	secretKeyPattern  = regexp.MustCompile(`(?i)\b(private_key|preshared_key|public_key|password|passwd|token|access_token|secret|key|uuid|auth|client_?id|cid|link|links|join)(["']?\s*[=:]\s*["']?)[^\s&"',;}\]]+`)
	joinLinkPattern   = regexp.MustCompile(`(?i)(/call/join/)[^\s?#&"'/]+`)
	userInfoPattern   = regexp.MustCompile(`//[^/@\s]+@`)
	opaqueSecretRegex = []*regexp.Regexp{
		regexp.MustCompile(`\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*`),
		regexp.MustCompile(`\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b`),
		regexp.MustCompile(`[A-Za-z0-9+/]{42,43}=`),
		// Hex keys and unpadded base64url codes; ordinary words and hostnames stay under 32 characters.
		regexp.MustCompile(`[A-Za-z0-9_-]{32,}`),
	}
)

func maskSecrets(s string) string {
	s = joinLinkPattern.ReplaceAllString(s, "$1***")
	s = secretKeyPattern.ReplaceAllString(s, "$1$2***")
	s = userInfoPattern.ReplaceAllString(s, "//***@")
	for _, p := range opaqueSecretRegex {
		s = p.ReplaceAllString(s, "***")
	}
	return s
}

func maskErr(err error) error {
	if err == nil {
		return nil
	}
	return errors.New(maskSecrets(err.Error()))
}
