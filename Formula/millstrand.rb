class Millstrand < Formula
  desc "Runtime for programming coding-agent constraints and loops"
  homepage "https://github.com/codethread/millstrand"
  url "https://github.com/codethread/millstrand.git",
      branch:   "main",
      revision: "a97acd3989dcb2c77f32ace8b9259704679dab3e"
  version "0.5.0"

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
