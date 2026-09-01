class Millstrand < Formula
  desc "Runtime for programming coding-agent constraints and loops"
  homepage "https://github.com/codethread/millstrand"
  url "https://github.com/codethread/millstrand.git",
      branch:   "main",
      revision: "8c94b0f8cae41ebc49e2418a8c2cbfddd99c87da"
  version "0.5.1"

  depends_on "go" => :build
  depends_on "clojure"

  def install
    ldflags = "-X millstrand-strand-cli/internal/config.InstalledSource=#{opt_libexec} " \
              "-X millstrand-strand-cli/internal/config.Version=#{version} " \
              "-X millstrand-strand-cli/internal/config.BuildID=8c94b0f8cae41ebc49e2418a8c2cbfddd99c87da"

    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"strand", "./cli/cmd/strand"
    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"mill", "./cli/cmd/mill"

    libexec.install Dir["*", ".[!.]*"]
  end

  test do
    expected_identity = {
      "build_id"         => "8c94b0f8cae41ebc49e2418a8c2cbfddd99c87da",
      "protocol_version" => 3,
      "version"          => "0.5.1",
    }

    assert_path_exists libexec/".millstrand/config.json"
    assert_path_exists libexec/"VERSION"
    assert_path_exists libexec/"CHANGELOG.md"
    assert_equal expected_identity, JSON.parse(shell_output("#{bin}/strand --version"))
    assert_equal expected_identity, JSON.parse(shell_output("#{bin}/mill --version"))
    assert_match "## 0.5.1", shell_output("#{bin}/mill changelog")
    assert_match "Millstrand", shell_output("#{bin}/mill prime millstrand")
  end
end
