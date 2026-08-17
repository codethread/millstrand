# Dynamic completions for the PWD-selected Strand weaver.

def strand-help [path: list<string>] {
    let result = (^strand help --json ...$path | complete)
    if $result.exit_code != 0 {
        return null
    }

    $result.stdout | from json
}

def child-nodes [help: record] {
    if "ops" in $help {
        $help.ops | each {|op| $op.node }
    } else {
        $help.node.children
    }
}

def completion-records [help: record, flags_only: bool] {
    let flags = if "ops" in $help {
        []
    } else {
        $help.node.invocation.flags
        | each {|flag| {
            value: $flag.flag
            description: $flag.doc
        } }
    }

    if $flags_only {
        return $flags
    }

    let children = child-nodes $help | each {|child| {
        value: $child.name
        description: $child.doc
    } }

    $children | append $flags
}

def nu-complete-strand [spans: list<string>] {
    let args = $spans | skip 1
    let partial = $args | last | default ""
    let preceding = if ($args | is-empty) {
        []
    } else {
        $args | drop 1
    }

    mut path = []
    mut help = strand-help $path
    if $help == null {
        return []
    }

    # Walk only declared subcommands. Once a token is a positional or flag
    # value, keep completing against the deepest command reached.
    for token in $preceding {
        if ($token | str starts-with "-") {
            continue
        }

        let child_names = child-nodes $help | get name
        if $token not-in $child_names {
            break
        }

        $path = $path | append $token
        $help = strand-help $path
        if $help == null {
            return []
        }
    }

    let completions = completion-records $help ($partial | str starts-with "-")
    if $partial == "" {
        return $completions
    }

    let needle = $partial | str lowercase
    $completions | where {|completion|
        $completion.value | str lowercase | str starts-with $needle
    }
}

@complete nu-complete-strand
export extern strand [
    ...args: string
]

