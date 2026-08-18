class Millstrand < Formula
  desc "Runtime for programming coding-agent constraints and loops"
  homepage "https://github.com/codethread/millstrand"
  url "https://github.com/codethread/millstrand.git",
      branch:   "main",
      revision: "3fdcb5ed8b7382d3b7d9a4e025b0120201e73a8d"
  version "0.4.0"

  depends_on "go" => :build
  depends_on "clojure"

  def install
    ldflags = "-X millstrand-strand-cli/internal/config.InstalledSource=#{opt_libexec} " \
              "-X millstrand-strand-cli/internal/config.BuildID=#{version}"

    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"strand", "./cli/cmd/strand"
    system "go", "build", "-buildvcs=false", "-ldflags", ldflags,
           "-o", bin/"mill", "./cli/cmd/mill"

    libexec.install Dir["*", ".[!.]*"]
  end

  test do
    assert_path_exists libexec/".millstrand/config.json"
    assert_match "Millstrand", shell_output("#{bin}/mill prime millstrand")
  end
end
