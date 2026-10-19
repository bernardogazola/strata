package br.pucpr.tcc.strata.analyzer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class RepositoryKeyTest {

  @ParameterizedTest(name = "{index}: {0} {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "POSIX|2f55736572732f616c6963652f776f726b2f61636d652d7061796d656e74732d6c6567616379|fa7da60406a5f3ea0cbf4335426a66d992d3c3d1|a6d309ef4118082d|8e86fb872d1fbafd6fcf984a150568f5c27198084f86f7edc8569f7ceb136993",
        "POSIX|2f55736572732f616c6963652f6176616c6961c3a7c3a36f|fa7da60406a5f3ea0cbf4335426a66d992d3c3d1|698e4ff468148f21|304b2324ba63d33832d59f46eca50676ef996be6f99e4eb957d82c4c5a53074d",
        "POSIX|2f55736572732f616c6963652f6176616c696163cca761cc836f|fa7da60406a5f3ea0cbf4335426a66d992d3c3d1|698e4ff468148f21|304b2324ba63d33832d59f46eca50676ef996be6f99e4eb957d82c4c5a53074d",
        "POSIX|2f55736572732f616c6963652fe18492e185a1e186abe18480e185b3e186af2d7265706f|fa7da60406a5f3ea0cbf4335426a66d992d3c3d1|3fb7baa8f8c44201|a76b1ea8fad7dfa442f7987567b2fb9c17d4bd2e603cb19af385a2f76726a268",
        "WINDOWS|433a5c55736572735c585c5265706f|961b02c3ce73d09e98efd9817e9639883c1e0dec|21f1a59a25571bc1|c10e997c6d7f26261238065f2cf0363a7c89076f8b44c7b394321356c521f66c",
        "WINDOWS|633a5c75736572735c785c5245504f|961b02c3ce73d09e98efd9817e9639883c1e0dec|21f1a59a25571bc1|c10e997c6d7f26261238065f2cf0363a7c89076f8b44c7b394321356c521f66c",
        "WINDOWS|5c5c7365727665725c53686172655c5265706f|961b02c3ce73d09e98efd9817e9639883c1e0dec|44d914bb96afb67c|6842f33ac287494c05ccec72c576323ce648fc1a896bf2b31fa16143f6424d1b",
        "WINDOWS|433a5c55736572735c4a4fc3834f5c5265706f|961b02c3ce73d09e98efd9817e9639883c1e0dec|c4fe20bb7c2d9b2f|66c4304ff54013efc9e70184c5dd9d0c7e6339686b2b95a11be2c12f7391a35f",
        "WINDOWS|433a5c55736572735cc4b04c4b45525c5265706f|961b02c3ce73d09e98efd9817e9639883c1e0dec|807f228457c70897|3c119ab9f3e9c0b13fdb9083127340a216ec6bd3a74fe53717ca334f95c06d40",
        "WINDOWS|433a5c55736572735c41cc8a6e67737472c3b66d|961b02c3ce73d09e98efd9817e9639883c1e0dec|0d68652e7afe842d|968cd1384cd0ddda29a93ca14e1c78f96562d48d5b839063822d0119a391f2f7",
        "POSIX|2f55736572732f616c6963652ff09f93a6207061636f7465732ff09d92ae7472617461|29d85578e75170a6c0eaebda4d701b46f1acf446|0960299a1afb630b|e15d1f9149425c1bac06c6828d597deaf9072610f989e4d57a73e022522a31bd",
        "POSIX|2f55736572732f616c6963652ff09d859e|29d85578e75170a6c0eaebda4d701b46f1acf446|ef5409ec912224bf|bd73549195349b1c49939ac86d1156035c450174984f282141a2f7a6f3d3b398",
        "POSIX|2f55736572732f616c6963652f776f726b2f61636d652d7061796d656e74732d6c6567616379||1e819ce41a8d925b|8e86fb872d1fbafd6fcf984a150568f5c27198084f86f7edc8569f7ceb136993",
        "POSIX|2f55736572732f616c6963652f776f726b2f61636d652d7061796d656e74732d6c6567616379|6571f8c2e01afbb9ea1162a62d5efc8030ad3e3814138b969c32fddd0f4d101b|b1a373716df85779|8e86fb872d1fbafd6fcf984a150568f5c27198084f86f7edc8569f7ceb136993",
        "POSIX|2f55736572732f616c6963652f615c62|fa7da60406a5f3ea0cbf4335426a66d992d3c3d1|87789ab29bac1bc0|79c69f2eb573db4637fdd906d47436fac9f458a6dd71702640475d2dce41aa9c",
        "WINDOWS|433a5c|961b02c3ce73d09e98efd9817e9639883c1e0dec|67a3d01926080d4d|471b002efc5a16ad357388602a4e5c0cb5b8abbcd77279f02bb795fe21e37ba6"
      })
  void derivaAChaveEAImpressaoDoOraculoPython(
      PathFlavor flavor, String pathHex, String root, String key, String fingerprint) {
    final String path = new String(HexFormat.of().parseHex(pathHex), StandardCharsets.UTF_8);

    final RepositoryIdentity identity = RepositoryIdentity.of(path, flavor, root);

    assertThat(identity.key().value()).isEqualTo(key);
    assertThat(identity.pathFingerprint().value()).isEqualTo(fingerprint);
    assertThat(identity.rootCommitId()).isEqualTo(root);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"A6D309EF4118082D", "a6d309ef4118082", "a6d309ef4118082dd", "g6d309ef41180820"})
  void recusaChaveForaDaGramatica(String invalida) {
    assertThatThrownBy(() -> new RepositoryKey(invalida))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "FA7DA60406A5F3EA0CBF4335426A66D992D3C3D1", "fa7da604", "null"})
  void recusaCommitRaizQueNaoEHexadecimalMinusculoDe40Ou64(String invalido) {
    assertThatThrownBy(
            () ->
                RepositoryIdentity.of(
                    "/Users/alice/work/acme-payments-legacy", PathFlavor.POSIX, invalido))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
