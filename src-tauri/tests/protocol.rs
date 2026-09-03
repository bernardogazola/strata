mod support;

use std::time::Duration;
use strata_lib::analyzer::{AnalyzerEvent, AnalyzerHandle, BackendPhase};
use strata_lib::error::{ErrorCode, Response};
use support::{Recorder, block_on, empty_payload, fast_config, stub_plan};

const SHORT: Duration = Duration::from_secs(5);

#[test]
fn spawn_and_handshake() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("handshake"), fast_config(), events.sink())
            .await
            .expect("o stub emite protocol.ready de imediato");

        assert_eq!(
            handle.backend_phase(),
            BackendPhase::Ready {
                protocol_version: 1
            }
        );

        let arrived = events.wait_for(
            |event| matches!(event, AnalyzerEvent::Wire { name, .. } if name == "protocol.ready"),
            SHORT,
        );
        assert!(
            arrived,
            "o frame de ready também deve chegar ao sink, mas veio {:?}",
            events.wire_names()
        );

        handle.shutdown().await.expect("shutdown limpo");
    });
}

#[test]
fn request_correlation_out_of_order() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("reorder3"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let a = handle.request("system.ping", empty_payload(), SHORT);
        let b = handle.request("system.ping", empty_payload(), SHORT);
        let c = handle.request("system.ping", empty_payload(), SHORT);
        let (a, b, c) = futures_join3(a, b, c).await;

        assert_eq!(payload_index(a.expect("a primeira resolve")), 0);
        assert_eq!(payload_index(b.expect("a segunda resolve")), 1);
        assert_eq!(payload_index(c.expect("a terceira resolve")), 2);

        handle.shutdown().await.expect("shutdown limpo");
    });
}

#[test]
fn oversized_line_rejected() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("oversized"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let first = handle
            .request("system.ping", empty_payload(), SHORT)
            .await
            .expect_err("um frame grande demais não pode resolver uma chamada");
        assert_eq!(first.code(), ErrorCode::MessageTooLarge);

        let diagnostics = handle.diagnostics();
        assert_eq!(diagnostics.malformed_count, 1);
        match diagnostics.last_fault {
            Some(fault) => assert_eq!(
                fault.code(),
                ErrorCode::MessageTooLarge,
                "o frame grande demais é a última falha"
            ),
            None => panic!("um frame grande demais deve ser registrado como falha"),
        }

        let second = handle
            .request("system.ping", empty_payload(), SHORT)
            .await
            .expect("o fluxo se ressincroniza depois de um frame grande demais");
        assert!(matches!(second, Response::Ok(_)));

        handle.shutdown().await.expect("shutdown limpo");
    });
}

#[test]
fn child_crash_rejects_pending() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("crash"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let a = handle.request("system.ping", empty_payload(), SHORT);
        let b = handle.request("system.ping", empty_payload(), SHORT);
        let c = handle.request("system.ping", empty_payload(), SHORT);
        let (a, b, c) = futures_join3(a, b, c).await;

        for outcome in [a, b, c] {
            let error = outcome.expect_err("um crash rejeita toda requisição pendente");
            assert_eq!(error.code(), ErrorCode::AnalyzerCrashed);
        }

        assert_eq!(
            handle.backend_phase(),
            BackendPhase::Crashed {
                exit_code: Some(70)
            }
        );
    });
}

#[test]
fn stdin_eof_terminates_child() {
    block_on(async {
        let events = Recorder::new();
        let handle =
            AnalyzerHandle::spawn(stub_plan("ignore-shutdown"), fast_config(), events.sink())
                .await
                .expect("stub pronto");

        let report = handle.shutdown().await.expect("o shutdown termina");
        assert_eq!(report.exit_code, Some(0));
        assert!(
            !report.forced,
            "fechar o stdin deve bastar; terminar à força significa que o caminho do EOF falhou"
        );

        events.settle(Duration::from_millis(50));

        assert!(
            !events.saw_crashed(),
            "um shutdown pedido nunca pode aparecer como CRASHED"
        );
    });
}

#[test]
fn stderr_drained_no_deadlock() {
    block_on(async {
        let events = Recorder::new();
        let handle = AnalyzerHandle::spawn(stub_plan("stderr-flood"), fast_config(), events.sink())
            .await
            .expect("stub pronto");

        let response = handle
            .request("system.ping", empty_payload(), SHORT)
            .await
            .expect("as respostas fluem enquanto o stderr transborda");
        assert!(matches!(response, Response::Ok(_)));

        let tail = handle.stderr_tail();
        assert!(tail.truncated, "um megabyte não pode ser retido inteiro");
        assert!(tail.lines.len() <= 2000);
        assert!(
            tail.lines.iter().any(|l| l.contains("flood 1023")),
            "o ring deve reter as linhas mais novas"
        );

        handle.shutdown().await.expect("shutdown limpo");
    });
}

async fn futures_join3<A, B, C>(a: A, b: B, c: C) -> (A::Output, B::Output, C::Output)
where
    A: std::future::Future,
    B: std::future::Future,
    C: std::future::Future,
{
    let mut a = Box::pin(a);
    let mut b = Box::pin(b);
    let mut c = Box::pin(c);
    let mut out_a = None;
    let mut out_b = None;
    let mut out_c = None;
    std::future::poll_fn(|cx| {
        if out_a.is_none()
            && let std::task::Poll::Ready(value) = a.as_mut().poll(cx)
        {
            out_a = Some(value);
        }
        if out_b.is_none()
            && let std::task::Poll::Ready(value) = b.as_mut().poll(cx)
        {
            out_b = Some(value);
        }
        if out_c.is_none()
            && let std::task::Poll::Ready(value) = c.as_mut().poll(cx)
        {
            out_c = Some(value);
        }
        if out_a.is_some() && out_b.is_some() && out_c.is_some() {
            std::task::Poll::Ready(())
        } else {
            std::task::Poll::Pending
        }
    })
    .await;
    (
        out_a.expect("executado até o fim"),
        out_b.expect("executado até o fim"),
        out_c.expect("executado até o fim"),
    )
}

fn payload_index(response: Response) -> u64 {
    let Response::Ok(raw) = response else {
        panic!("esperava uma resposta ok");
    };
    let text = raw.get();
    let marker = "\"index\":";
    let start = text.find(marker).expect("o stub inclui um índice") + marker.len();
    text[start..]
        .trim_end_matches('}')
        .trim()
        .parse()
        .expect("o índice é um inteiro")
}
