class Millstrand < Formula
  desc "Runtime for programming coding-agent constraints and loops"
  homepage "https://github.com/codethread/millstrand"
  url "https://github.com/codethread/millstrand.git",
      branch:   "main",
      revision: "4bf19b55baba6dc67845a37e1f6ed23d434078c9"
  version "0.6.0"

  depends_on "go" => :build
  depends_on "clojure"

  def install
    ldflags = "-X millstrand-strand-cli/internal/config.InstalledSource=#{opt_libexec} " \
              "-X millstrand-strand-cli/internal/config.Version=#{version} " \
              "-X millstrand-strand-cli/internal/config.BuildID=4bf19b55baba6dc67845a37e1f6ed23d434078c9"

    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"strand", "./cli/cmd/strand"
    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"mill", "./cli/cmd/mill"

    prefix.install "CHANGELOG.md"
    libexec.install Dir["*", ".[!.]*"]
    libexec.install_symlink prefix/"CHANGELOG.md"
  end

  test do
    expected_identity = {
      "build_id"         => "4bf19b55baba6dc67845a37e1f6ed23d434078c9",
      "protocol_version" => 3,
      "version"          => "0.6.0",
    }

    assert_path_exists libexec/".millstrand/config.json"
    assert_path_exists libexec/"VERSION"
    assert_path_exists libexec/"CHANGELOG.md"
    assert_equal expected_identity, JSON.parse(shell_output("#{bin}/strand --version"))
    assert_equal expected_identity, JSON.parse(shell_output("#{bin}/mill --version"))
    assert_match "## 0.6.0", shell_output("#{bin}/mill changelog")
  end
end
